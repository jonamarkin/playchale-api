package com.playchale.api.admin.internal.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.shared.events.Happened;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a PlayChale person needs to answer for the app: who someone is, what happened to them, what
 * is being said, and how the whole thing is doing.
 *
 * <p>It reads the tables directly rather than through each module's API. That is a deliberate
 * trade: an admin desk is cross-cutting by nature and would otherwise need a reporting method added
 * to every module for every question anyone ever asks. The cost is that these queries know table
 * shapes, so a module changing its schema can break them — which is what the tests here are for.
 *
 * <p>Every call takes the staff member making it, and every one of them goes through
 * {@link Staff#require}. Looking at a player's record is itself an act, so the ones that reveal
 * personal detail are written to the event stream with the staff member's name on them.
 */
@Service
public class AdminDesk {

	private final Staff staff;

	private final Happened happened;

	private final JdbcClient jdbc;

	private final Clock clock;

	AdminDesk(Staff staff, Happened happened, JdbcClient jdbc, Clock clock) {
		this.staff = staff;
		this.happened = happened;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** Someone as the desk sees them: enough to recognise them, not their whole life. */
	public record Person(UUID id, String name, String handle, String phone, String email, String area, String country, Instant joinedAt,
			Instant deletedAt, int games, boolean staff) {
	}

	/** One line of someone's history, for answering "what happened to me". */
	public record Entry(Instant at, String what, String detail) {
	}

	/** A message, for moderation. */
	public record Message(UUID id, UUID gameId, String gameTitle, UUID saidBy, String saidByName, String body, Instant at) {
	}

	/** How the app is doing over a window. */
	public record Health(long signups, long gamesCreated, long gamesPlayed, long gamesCalledOff, long joins, long messages,
			long activePeople) {
	}

	/**
	 * Finds people by name, handle, phone or email. A blank search returns the newest, so the screen
	 * has something in it rather than an empty box.
	 */
	@Transactional(readOnly = true)
	public List<Person> find(UUID by, String query, int limit) {
		staff.require(by);
		var term = query == null ? "" : query.strip();
		// Numbers are stored as +233244555125 and typed as 024 455 5125, so the typed digits are
		// matched without their local leading zero. Short runs are ignored, or "1" finds everyone.
		var digits = term.replaceAll("\\D", "").replaceFirst("^0+", "");
		var rows = jdbc.sql("""
				SELECT u.id, u.name, u.handle, u.phone, u.email, u.area, u.country, u.created_at AS joined_at, u.deleted_at,
				       (SELECT count(*) FROM game_participants p WHERE p.user_id = u.id) AS games,
				       (SELECT count(*) > 0 FROM platform_staff s WHERE s.user_id = u.id) AS staff
				FROM users u
				WHERE CAST(:term AS text) = ''
				   OR u.name ILIKE '%' || CAST(:term AS text) || '%'
				   OR u.handle ILIKE '%' || CAST(:term AS text) || '%'
				   OR u.email ILIKE '%' || CAST(:term AS text) || '%'
				   OR (length(CAST(:digits AS text)) >= 6 AND u.phone LIKE '%' || CAST(:digits AS text) || '%')
				ORDER BY u.created_at DESC
				LIMIT :limit
				""").param("term", term).param("digits", digits).param("limit", Math.clamp(limit <= 0 ? 25 : limit, 1, 100))
			.query(Person.class).list();
		if (!term.isEmpty()) {
			// Searching for a person is itself an act: who looked, and for what.
			happened.record("admin.searched", "user", null, by, Map.of("term", term, "found", rows.size()));
		}
		return rows;
	}

	/**
	 * What happened to one person, newest first: the answer to "I paid but it says I didn't".
	 * Looking at it is recorded, because it shows their games and their money.
	 */
	@Transactional(readOnly = true)
	public List<Entry> history(UUID by, UUID userId, int limit) {
		staff.require(by);
		happened.record("admin.viewed-person", "user", userId, by, Map.of());
		return jdbc.sql("""
				SELECT at, what, detail FROM (
				  SELECT p.joined_at AS at, 'Joined a game' AS what, g.title AS detail
				  FROM game_participants p JOIN games g ON g.id = p.game_id WHERE p.user_id = :id
				  UNION ALL
				  SELECT d.left_at, 'Left a game', g.title || ' · ' || d.notice_minutes || ' minutes before'
				  FROM game_departures d JOIN games g ON g.id = d.game_id WHERE d.user_id = :id
				  UNION ALL
				  SELECT m.created_at, 'Moved money', m.direction || ' ' || m.amount || ' ' || m.currency
				  FROM movements m WHERE m.user_id = :id
				  UNION ALL
				  -- What they said, so a decision about a message can see whether it is the first.
				  SELECT gm.created_at, 'Said in ' || g.title, gm.body
				  FROM game_messages gm JOIN games g ON g.id = gm.game_id WHERE gm.user_id = :id
				  UNION ALL
				  SELECT e.occurred_at, 'Event: ' || e.event_type, coalesce(e.details ->> 'note', '')
				  FROM audit_events e WHERE e.actor_id = :id
				) h
				ORDER BY at DESC
				LIMIT :limit
				""").param("id", userId).param("limit", Math.clamp(limit <= 0 ? 50 : limit, 1, 200)).query(Entry.class).list();
	}

	/** Everything said in games lately, newest first, so something out of order can be found. */
	@Transactional(readOnly = true)
	public List<Message> messages(UUID by, int limit) {
		staff.require(by);
		return jdbc.sql("""
				SELECT m.id, m.game_id, g.title AS game_title, m.user_id AS said_by, u.name AS said_by_name, m.body, m.created_at AS at
				FROM game_messages m
				JOIN games g ON g.id = m.game_id
				JOIN users u ON u.id = m.user_id
				ORDER BY m.created_at DESC
				LIMIT :limit
				""").param("limit", Math.clamp(limit <= 0 ? 50 : limit, 1, 200)).query(Message.class).list();
	}

	/**
	 * Takes a message down. The host can already remove one from their own game; this is for when
	 * nobody in the game will, and it is recorded against the staff member who did it.
	 */
	@Transactional
	public void removeMessage(UUID by, UUID messageId, String why) {
		staff.require(by);
		var gone = jdbc.sql("DELETE FROM game_messages WHERE id = :id").param("id", messageId).update();
		if (gone == 0) {
			throw BusinessException.notFound("That message is already gone.");
		}
		happened.record("admin.removed-message", "message", messageId, by, Map.of("why", why == null ? "" : why));
	}

	/** How the app is doing over the last {@code days}. */
	@Transactional(readOnly = true)
	public Health health(UUID by, int days) {
		staff.require(by);
		var since = clock.instant().minus(Duration.ofDays(Math.clamp(days <= 0 ? 30 : days, 1, 365))).atOffset(ZoneOffset.UTC);
		return jdbc.sql("""
				SELECT (SELECT count(*) FROM users WHERE created_at >= :since AND deleted_at IS NULL)              AS signups,
				       (SELECT count(*) FROM games WHERE created_at >= :since)                                     AS games_created,
				       (SELECT count(*) FROM game_results r JOIN games g ON g.id = r.game_id
				         WHERE g.starts_at >= :since)                                                              AS games_played,
				       (SELECT count(*) FROM games WHERE created_at >= :since AND status = 'cancelled')            AS games_called_off,
				       -- Every join in the window, whether the spot was kept or later given up (V32 keeps
				       -- joined_at on a departure). Not the event stream: it only began recording at V35,
				       -- so for anything older it would say nobody joined while active_people said nine did.
				       (SELECT count(*) FROM game_participants WHERE user_id IS NOT NULL AND joined_at >= :since)
				       + (SELECT count(*) FROM game_departures WHERE joined_at >= :since)                       AS joins,
				       (SELECT count(*) FROM game_messages WHERE created_at >= :since)                             AS messages,
				       (SELECT count(DISTINCT user_id) FROM game_participants WHERE joined_at >= :since)           AS active_people
				""").param("since", since).query(Health.class).single();
	}

	/**
	 * Everything held about one person, for a data request. Deletion already worked; this is the
	 * other half someone is entitled to ask for, and asking for it is itself recorded.
	 */
	@Transactional(readOnly = true)
	public Map<String, Object> export(UUID by, UUID userId) {
		staff.require(by);
		happened.record("admin.exported-person", "user", userId, by, Map.of());
		return Map.of(
				"user", one("SELECT * FROM users WHERE id = :id", userId),
				"games", rows("SELECT g.* FROM games g JOIN game_participants p ON p.game_id = g.id WHERE p.user_id = :id", userId),
				"spots", rows("SELECT * FROM game_participants WHERE user_id = :id", userId),
				"departures", rows("SELECT * FROM game_departures WHERE user_id = :id", userId),
				"messages", rows("SELECT * FROM game_messages WHERE user_id = :id", userId),
				"payments", rows("SELECT * FROM payments WHERE user_id = :id", userId),
				"movements", rows("SELECT * FROM movements WHERE user_id = :id", userId),
				"notifications", rows("SELECT * FROM notifications WHERE user_id = :id", userId),
				"results", rows("SELECT * FROM result_players WHERE user_id = :id", userId));
	}

	private List<Map<String, Object>> rows(String sql, UUID userId) {
		return jdbc.sql(sql).param("id", userId).query().listOfRows();
	}

	private Map<String, Object> one(String sql, UUID userId) {
		var found = jdbc.sql(sql).param("id", userId).query().listOfRows();
		return found.isEmpty() ? Map.of() : found.get(0);
	}

}
