package com.playchale.api.admin.internal.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.playchale.api.games.api.GameModeration;
import com.playchale.api.integration.sms.SmsBalance;
import com.playchale.api.reports.api.ReportDecisions;
import com.playchale.api.venues.api.VenueReview;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.shared.events.Happened;
import org.springframework.beans.factory.ObjectProvider;
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

	private final ObjectProvider<SmsBalance> smsBalance;

	private final GameModeration moderation;

	private final VenueReview venueReview;

	private final ReportDecisions reportDecisions;

	AdminDesk(Staff staff, Happened happened, JdbcClient jdbc, Clock clock, ObjectProvider<SmsBalance> smsBalance, GameModeration moderation,
			VenueReview venueReview, ReportDecisions reportDecisions) {
		this.staff = staff;
		this.happened = happened;
		this.jdbc = jdbc;
		this.clock = clock;
		this.smsBalance = smsBalance;
		this.moderation = moderation;
		this.venueReview = venueReview;
		this.reportDecisions = reportDecisions;
	}

	/**
	 * The SMS bundle, which sign-in codes by phone draw on.
	 *
	 * @param configured whether texts go out at all (an SMS provider is set up)
	 * @param credits    texts left, or null when the provider couldn't be asked just now
	 * @param low        below the point where it's time to top up
	 */
	public record Sms(boolean configured, Long credits, long lowAt, boolean low) {
	}

	/** How many texts are left. Asked of the provider each time: it's the provider's number, not ours. */
	public Sms sms(UUID by) {
		staff.require(by);
		var balance = smsBalance.getIfAvailable();
		if (balance == null) {
			return new Sms(false, null, 0, false);
		}
		var credits = balance.credits();
		return new Sms(true, credits.isPresent() ? credits.getAsLong() : null, balance.lowAt(),
				credits.isPresent() && credits.getAsLong() < balance.lowAt());
	}

	/**
	 * Someone as the desk sees them: enough to recognise them, not their whole life.
	 *
	 * @param guestHost hosting without an account: a name and a number, no way to sign in yet
	 */
	public record Person(UUID id, String name, String handle, String phone, String email, String area, String country, Instant joinedAt,
			Instant deletedAt, int games, boolean staff, boolean guestHost) {
	}

	/** One line of someone's history, for answering "what happened to me". */
	public record Entry(Instant at, String what, String detail) {
	}

	/** A message, for moderation. */
	public record Message(UUID id, UUID gameId, String gameTitle, UUID saidBy, String saidByName, String body, Instant at) {
	}

	/**
	 * How the app is doing over a window.
	 *
	 * @param onboarded    of the signups, those who finished setting up their profile
	 * @param eventPeople  people put in events (by an organiser, a rep or the link)
	 * @param eventResults matches and heats with their result put in
	 */
	public record Health(long signups, long onboarded, long gamesCreated, long gamesPlayed, long gamesCalledOff, long joins,
			long messages, long activePeople, long bookings, long eventsCreated, long eventPeople, long eventResults) {
	}

	/**
	 * The web app's pages and films over a window (usage_counts): how often, never who.
	 *
	 * @param opens    pages opened
	 * @param arrivals visits: the first page of each, the only one that says where it came from
	 */
	public record Visits(long opens, long arrivals, List<Day> days, List<Page> pages, List<Count> sources, List<Film> films) {
	}

	public record Day(String day, long opens) {
	}

	/** A page, by its route, with how often it was opened and how often a visit began on it. */
	public record Page(String name, long opens, long arrivals) {
	}

	public record Count(String name, long n) {
	}

	/** A film: how often it started (it plays on its own once in view) and how often it was watched to the end. */
	public record Film(String name, long started, long finished) {
	}

	/**
	 * How things went, period by period, for drawing: by day over a month or less, by week over a
	 * season, by month over a year. Every period is there, a quiet one as zeros.
	 *
	 * @param unit "day", "week" (from Monday) or "month"
	 */
	public record Trends(String unit, List<Bucket> buckets) {
	}

	/** One period: the day it starts, and what happened in it. Visits are counted without knowing who. */
	public record Bucket(String start, long signups, long activePeople, long gamesCreated, long gamesPlayed, long eventPeople, long visits) {
	}

	/**
	 * From a visit to doing something. The first two steps are counted without knowing who (pages,
	 * not people), so the steps are counts side by side, not the same people followed through.
	 *
	 * @param openedForm   create pages opened: a game, an event, a venue, a team, a league
	 * @param didSomething of the new accounts, those who hosted or joined a game, are in an event,
	 *                     run a workspace or list a venue
	 */
	public record Funnel(long visits, long openedForm, long signups, long didSomething, Guests guests) {
	}

	/**
	 * Using PlayChale without an account, and how many then signed up.
	 *
	 * @param hostsStarted    games hosted with just a name and a number
	 * @param hostsKept       of guest hosts, those who signed up (or moved their games to an account)
	 * @param eventGuests     people who joined a games day through its link without an account
	 * @param eventGuestsKept of them, those whose place is now on an account
	 * @param gameGuests      spots taken in games without an account
	 * @param gameGuestsKept  spots claimed by an account since
	 */
	public record Guests(long hostsStarted, long hostsKept, long eventGuests, long eventGuestsKept, long gameGuests, long gameGuestsKept) {
	}

	/**
	 * A games day or corporate games, as the desk lists it.
	 *
	 * @param live         on today, where it's held
	 * @param guests       of the people, those who joined without an account
	 * @param resultsIn    matches and heats with a result put in
	 * @param resultsTotal matches and heats drawn
	 */
	public record EventRow(UUID id, String name, String organisation, String country, LocalDate startsOn, LocalDate endsOn, String status,
			boolean live, long people, long guests, long games, long resultsIn, long resultsTotal, String publicSlug, Instant createdAt) {
	}

	/** One event, with who runs it (to call on the day) and how each of its games is getting on. */
	public record EventDetail(EventRow event, List<Organiser> organisers, List<EventGame> games) {
	}

	/** Someone who runs the event's workspace. Their number and email are why looking is recorded. */
	public record Organiser(UUID id, String name, String phone, String email, String role) {
	}

	public record EventGame(UUID id, String name, String entryKind, String format, String status, long entries, long resultsIn, long resultsTotal) {
	}

	/**
	 * A game, as the desk lists it: enough to tell a real one from a fake.
	 *
	 * @param hostGuest its host has no account (a guest host)
	 * @param fixture   a competition's game, which only the competition changes
	 */
	public record GameRow(UUID id, String title, String sport, String format, Instant startsAt, String venueName, String venueArea, UUID hostId,
			String hostName, boolean hostGuest, String visibility, long players, Integer capacity, String status, String cancelReason,
			Instant createdAt, long messages, boolean fixture) {
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
				       (SELECT count(*) > 0 FROM platform_staff s WHERE s.user_id = u.id) AS staff,
				       u.guest AS guest_host
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
				  SELECT s.created_at, 'Started a repeating game', s.title || ' · ' || s.frequency || ' · ' || s.status
				  FROM game_series s WHERE s.host_id = :id
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
				SELECT (SELECT count(*) FROM users WHERE created_at >= :since AND deleted_at IS NULL AND NOT guest) AS signups,
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
				       (SELECT count(DISTINCT user_id) FROM game_participants WHERE joined_at >= :since)           AS active_people,
				       (SELECT count(*) FROM users WHERE created_at >= :since AND deleted_at IS NULL AND onboarded) AS onboarded,
				       (SELECT count(*) FROM bookings WHERE created_at >= :since)                                  AS bookings,
				       (SELECT count(*) FROM events WHERE created_at >= :since)                                    AS events_created,
				       (SELECT count(*) FROM event_people WHERE created_at >= :since)                              AS event_people,
				       (SELECT count(*) FROM event_matches WHERE recorded_at >= :since)
				       + (SELECT count(*) FROM event_heats WHERE recorded_at >= :since)                         AS event_results
				""").param("since", since).query(Health.class).single();
	}

	/** The web app's pages and films over the last {@code days}, the busiest first. */
	@Transactional(readOnly = true)
	public Visits visits(UUID by, int days) {
		staff.require(by);
		var since = clock.instant().minus(Duration.ofDays(Math.clamp(days <= 0 ? 30 : days, 1, 365) - 1L))
			.atOffset(ZoneOffset.UTC).toLocalDate();
		var totals = jdbc.sql("""
				SELECT coalesce(sum(n), 0) AS opens, coalesce(sum(n) FILTER (WHERE source <> ''), 0) AS arrivals
				FROM usage_counts WHERE kind = 'page' AND day >= :since
				""").param("since", since).query().singleRow();
		var perDay = jdbc.sql("""
				SELECT to_char(day, 'YYYY-MM-DD') AS day, sum(n) AS opens FROM usage_counts
				WHERE kind = 'page' AND day >= :since GROUP BY day ORDER BY day
				""").param("since", since).query(Day.class).list();
		var pages = jdbc.sql("""
				SELECT name, sum(n) AS opens, coalesce(sum(n) FILTER (WHERE source <> ''), 0) AS arrivals FROM usage_counts
				WHERE kind = 'page' AND day >= :since GROUP BY name ORDER BY opens DESC, name LIMIT 30
				""").param("since", since).query(Page.class).list();
		var sources = jdbc.sql("""
				SELECT source AS name, sum(n) AS n FROM usage_counts
				WHERE kind = 'page' AND source <> '' AND day >= :since GROUP BY source ORDER BY n DESC, source LIMIT 20
				""").param("since", since).query(Count.class).list();
		var films = jdbc.sql("""
				SELECT name, coalesce(sum(n) FILTER (WHERE kind = 'film-start'), 0) AS started,
				       coalesce(sum(n) FILTER (WHERE kind = 'film-end'), 0) AS finished
				FROM usage_counts WHERE kind IN ('film-start', 'film-end') AND day >= :since
				GROUP BY name ORDER BY started DESC, name
				""").param("since", since).query(Film.class).list();
		return new Visits(((Number) totals.get("opens")).longValue(), ((Number) totals.get("arrivals")).longValue(), perDay, pages,
				sources, films);
	}

	/** How things went over the last {@code days}, period by period, oldest first. */
	@Transactional(readOnly = true)
	public Trends trends(UUID by, int days) {
		staff.require(by);
		var window = Math.clamp(days <= 0 ? 30 : days, 1, 365);
		var unit = window <= 31 ? "day" : window <= 120 ? "week" : "month";
		var today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
		var first = periodOf(today.minusDays(window - 1L), unit);
		var signups = perPeriod("""
				SELECT date_trunc(CAST(:unit AS text), created_at AT TIME ZONE 'UTC')::date AS period, count(*) AS n
				FROM users WHERE created_at >= :from AND deleted_at IS NULL AND NOT guest GROUP BY 1
				""", unit, first);
		var active = perPeriod("""
				SELECT date_trunc(CAST(:unit AS text), joined_at AT TIME ZONE 'UTC')::date AS period, count(DISTINCT user_id) AS n
				FROM game_participants WHERE joined_at >= :from AND user_id IS NOT NULL GROUP BY 1
				""", unit, first);
		var created = perPeriod("""
				SELECT date_trunc(CAST(:unit AS text), created_at AT TIME ZONE 'UTC')::date AS period, count(*) AS n
				FROM games WHERE created_at >= :from GROUP BY 1
				""", unit, first);
		var played = perPeriod("""
				SELECT date_trunc(CAST(:unit AS text), g.starts_at AT TIME ZONE 'UTC')::date AS period, count(*) AS n
				FROM game_results r JOIN games g ON g.id = r.game_id WHERE g.starts_at >= :from GROUP BY 1
				""", unit, first);
		var eventPeople = perPeriod("""
				SELECT date_trunc(CAST(:unit AS text), created_at AT TIME ZONE 'UTC')::date AS period, count(*) AS n
				FROM event_people WHERE created_at >= :from GROUP BY 1
				""", unit, first);
		var visits = perPeriod("""
				SELECT date_trunc(CAST(:unit AS text), CAST(day AS timestamp))::date AS period, sum(n) AS n
				FROM usage_counts WHERE kind = 'page' AND source <> '' AND day >= :fromDay GROUP BY 1
				""", unit, first);
		var buckets = new ArrayList<Bucket>();
		for (var start = first; !start.isAfter(today); start = next(start, unit)) {
			buckets.add(new Bucket(start.toString(), signups.getOrDefault(start, 0L), active.getOrDefault(start, 0L), created.getOrDefault(start, 0L),
					played.getOrDefault(start, 0L), eventPeople.getOrDefault(start, 0L), visits.getOrDefault(start, 0L)));
		}
		return new Trends(unit, buckets);
	}

	private Map<LocalDate, Long> perPeriod(String sql, String unit, LocalDate from) {
		var counts = new HashMap<LocalDate, Long>();
		jdbc.sql(sql).param("unit", unit).param("from", from.atStartOfDay().atOffset(ZoneOffset.UTC)).param("fromDay", from)
			.query((row, n) -> Map.entry(row.getObject("period", LocalDate.class), row.getLong("n")))
			.list().forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
		return counts;
	}

	/** The start of the period a day is in: itself, its week's Monday, or its month's first. */
	private static LocalDate periodOf(LocalDate day, String unit) {
		return switch (unit) {
			case "week" -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
			case "month" -> day.withDayOfMonth(1);
			default -> day;
		};
	}

	private static LocalDate next(LocalDate start, String unit) {
		return switch (unit) {
			case "week" -> start.plusWeeks(1);
			case "month" -> start.plusMonths(1);
			default -> start.plusDays(1);
		};
	}

	/** Pages a visitor opens to make something: where "set it up before signing up" starts. */
	private static final List<String> CREATE_PAGES = List.of("/games/new", "/events/new", "/venues/new", "/teams/new", "/competitions/new");

	/** From visiting to doing something, and using PlayChale without an account, over the last {@code days}. */
	@Transactional(readOnly = true)
	public Funnel funnel(UUID by, int days) {
		staff.require(by);
		var window = Math.clamp(days <= 0 ? 30 : days, 1, 365);
		var since = clock.instant().minus(Duration.ofDays(window)).atOffset(ZoneOffset.UTC);
		// Visits are counted by day, so the window is whole days, as on the visits table.
		var sinceDay = clock.instant().minus(Duration.ofDays(window - 1L)).atOffset(ZoneOffset.UTC).toLocalDate();
		var row = jdbc.sql("""
				SELECT (SELECT coalesce(sum(n), 0) FROM usage_counts WHERE kind = 'page' AND source <> '' AND day >= :sinceDay) AS visits,
				       (SELECT coalesce(sum(n), 0) FROM usage_counts WHERE kind = 'page' AND day >= :sinceDay AND name IN (:pages)) AS opened_form,
				       (SELECT count(*) FROM users WHERE created_at >= :since AND deleted_at IS NULL AND NOT guest) AS signups,
				       (SELECT count(*) FROM users u WHERE u.created_at >= :since AND u.deleted_at IS NULL AND NOT u.guest AND (
				            EXISTS (SELECT 1 FROM games g WHERE g.host_id = u.id)
				         OR EXISTS (SELECT 1 FROM game_participants p WHERE p.user_id = u.id)
				         OR EXISTS (SELECT 1 FROM game_departures d WHERE d.user_id = u.id)
				         OR EXISTS (SELECT 1 FROM event_people e WHERE e.user_id = u.id)
				         OR EXISTS (SELECT 1 FROM organisation_memberships m WHERE m.user_id = u.id)
				         OR EXISTS (SELECT 1 FROM venues v WHERE v.owner_id = u.id))) AS did_something,
				       (SELECT count(*) FROM audit_events WHERE event_type = 'guest-host.started' AND occurred_at >= :since) AS hosts_started,
				       (SELECT count(*) FROM audit_events WHERE event_type = 'guest-host.kept' AND occurred_at >= :since) AS hosts_kept,
				       (SELECT count(*) FROM event_people WHERE source = 'guest' AND created_at >= :since) AS event_guests,
				       (SELECT count(*) FROM event_people WHERE source = 'guest' AND created_at >= :since AND user_id IS NOT NULL) AS event_guests_kept,
				       (SELECT count(*) FROM audit_events WHERE event_type = 'game.guest-joined' AND occurred_at >= :since) AS game_guests,
				       (SELECT count(*) FROM audit_events WHERE event_type = 'game.guest-spot-claimed' AND occurred_at >= :since) AS game_guests_kept
				""").param("since", since).param("sinceDay", sinceDay).param("pages", CREATE_PAGES).query().singleRow();
		return new Funnel(count(row, "visits"), count(row, "opened_form"), count(row, "signups"), count(row, "did_something"),
				new Guests(count(row, "hosts_started"), count(row, "hosts_kept"), count(row, "event_guests"), count(row, "event_guests_kept"),
						count(row, "game_guests"), count(row, "game_guests_kept")));
	}

	private static long count(Map<String, Object> row, String column) {
		return ((Number) row.get(column)).longValue();
	}

	/**
	 * An event's row. "Today" is the day where it's held, so a games day in Accra is live on Accra's
	 * Saturday whatever the server's clock says.
	 */
	private static final String EVENT_ROWS = """
			SELECT e.id, e.name, o.name AS organisation, e.country, e.starts_on, e.ends_on, e.status,
			       (e.status = 'open' AND (CAST(:now AS timestamptz) AT TIME ZONE e.timezone)::date BETWEEN e.starts_on AND e.ends_on) AS live,
			       (SELECT count(*) FROM event_people p WHERE p.event_id = e.id) AS people,
			       (SELECT count(*) FROM event_people p WHERE p.event_id = e.id AND p.source = 'guest') AS guests,
			       (SELECT count(*) FROM event_games g WHERE g.event_id = e.id) AS games,
			       (SELECT count(*) FROM event_matches m JOIN event_games g ON g.id = m.game_id WHERE g.event_id = e.id AND m.recorded_at IS NOT NULL)
			       + (SELECT count(*) FROM event_heats h JOIN event_games g ON g.id = h.game_id WHERE g.event_id = e.id AND h.recorded_at IS NOT NULL) AS results_in,
			       (SELECT count(*) FROM event_matches m JOIN event_games g ON g.id = m.game_id WHERE g.event_id = e.id)
			       + (SELECT count(*) FROM event_heats h JOIN event_games g ON g.id = h.game_id WHERE g.event_id = e.id) AS results_total,
			       e.public_slug, e.created_at
			FROM events e JOIN organisations o ON o.id = e.organisation_id
			""";

	/** Which events, and in what order: coming up (soonest first), been, or called off. */
	private static final Map<String, String[]> EVENTS_WHEN = Map.of(
			"upcoming", new String[] { "e.status = 'open' AND e.ends_on >= (CAST(:now AS timestamptz) AT TIME ZONE e.timezone)::date",
					"e.starts_on, e.name" },
			"past", new String[] { "e.status = 'finished' OR (e.status = 'open' AND e.ends_on < (CAST(:now AS timestamptz) AT TIME ZONE e.timezone)::date)",
					"e.starts_on DESC, e.name" },
			"cancelled", new String[] { "e.status = 'cancelled'", "e.starts_on DESC, e.name" });

	/** Games days and corporate games: coming up, been, or called off, found by their name or whose they are. */
	@Transactional(readOnly = true)
	public List<EventRow> events(UUID by, String when, String query, int limit) {
		staff.require(by);
		var which = EVENTS_WHEN.getOrDefault(when == null ? "upcoming" : when, EVENTS_WHEN.get("upcoming"));
		var term = query == null ? "" : query.strip();
		var sql = EVENT_ROWS + "WHERE (" + which[0] + ")\n"
				+ "  AND (CAST(:term AS text) = '' OR e.name ILIKE '%' || CAST(:term AS text) || '%' OR o.name ILIKE '%' || CAST(:term AS text) || '%')\n"
				+ "ORDER BY " + which[1] + "\nLIMIT :limit";
		return jdbc.sql(sql).param("now", clock.instant().atOffset(ZoneOffset.UTC)).param("term", term)
			.param("limit", Math.clamp(limit <= 0 ? 50 : limit, 1, 200)).query(EventRow.class).list();
	}

	/**
	 * One event: who runs it, with their number and email for calling them on the day, and each
	 * game's progress. Seeing those contacts is recorded, as looking someone up is.
	 */
	@Transactional(readOnly = true)
	public EventDetail event(UUID by, UUID eventId) {
		staff.require(by);
		var row = jdbc.sql(EVENT_ROWS + "WHERE e.id = :id").param("now", clock.instant().atOffset(ZoneOffset.UTC)).param("id", eventId)
			.query(EventRow.class).optional().orElseThrow(() -> BusinessException.notFound("That event isn’t there any more."));
		happened.record("admin.viewed-event", "event", eventId, by, Map.of());
		var organisers = jdbc.sql("""
				SELECT u.id, u.name, nullif(u.phone, '') AS phone, coalesce(u.email, u.sign_in_email) AS email, m.role
				FROM events e
				JOIN organisation_memberships m ON m.organisation_id = e.organisation_id
				JOIN users u ON u.id = m.user_id
				WHERE e.id = :id AND u.deleted_at IS NULL
				ORDER BY m.role = 'owner' DESC, u.name
				""").param("id", eventId).query(Organiser.class).list();
		var games = jdbc.sql("""
				SELECT g.id, g.name, g.entry_kind, g.format, g.status,
				       (SELECT count(*) FROM event_entries x WHERE x.game_id = g.id) AS entries,
				       (SELECT count(*) FROM event_matches m WHERE m.game_id = g.id AND m.recorded_at IS NOT NULL)
				       + (SELECT count(*) FROM event_heats h WHERE h.game_id = g.id AND h.recorded_at IS NOT NULL) AS results_in,
				       (SELECT count(*) FROM event_matches m WHERE m.game_id = g.id)
				       + (SELECT count(*) FROM event_heats h WHERE h.game_id = g.id) AS results_total
				FROM event_games g WHERE g.event_id = :id
				ORDER BY g.name
				""").param("id", eventId).query(EventGame.class).list();
		return new EventDetail(row, organisers, games);
	}

	/** Which games, and in what order: newest made (where spam shows), coming up, been played, or called off. */
	private static final Map<String, String[]> GAMES_WHEN = Map.of(
			"new", new String[] { "TRUE", "g.created_at DESC" },
			"upcoming", new String[] { "g.status <> 'cancelled' AND g.starts_at >= :now", "g.starts_at" },
			"played", new String[] { "g.status <> 'cancelled' AND g.starts_at < :now", "g.starts_at DESC" },
			"cancelled", new String[] { "g.status = 'cancelled'", "coalesce(g.cancelled_at, g.created_at) DESC" });

	/** Games, found by their title, where they're played or who hosts them. */
	@Transactional(readOnly = true)
	public List<GameRow> games(UUID by, String when, String query, int limit) {
		staff.require(by);
		var which = GAMES_WHEN.getOrDefault(when == null ? "new" : when, GAMES_WHEN.get("new"));
		var term = query == null ? "" : query.strip();
		var sql = """
				SELECT g.id, g.title, g.sport, g.format, g.starts_at, g.venue_name, g.venue_area, g.host_id, u.name AS host_name,
				       coalesce(u.guest, false) AS host_guest, g.visibility,
				       (SELECT count(*) FROM game_participants p WHERE p.game_id = g.id) AS players, g.capacity, g.status, g.cancel_reason,
				       g.created_at, (SELECT count(*) FROM game_messages m WHERE m.game_id = g.id) AS messages,
				       g.competition_id IS NOT NULL AS fixture
				FROM games g LEFT JOIN users u ON u.id = g.host_id
				WHERE (CAST(:term AS text) = '' OR g.title ILIKE '%' || CAST(:term AS text) || '%'
				       OR g.venue_name ILIKE '%' || CAST(:term AS text) || '%' OR u.name ILIKE '%' || CAST(:term AS text) || '%')
				""" + "  AND (" + which[0] + ")\nORDER BY " + which[1] + "\nLIMIT :limit";
		return jdbc.sql(sql).param("now", clock.instant().atOffset(ZoneOffset.UTC)).param("term", term)
			.param("limit", Math.clamp(limit <= 0 ? 50 : limit, 1, 200)).query(GameRow.class).list();
	}

	/**
	 * Takes a game down: a fake, spam, something that shouldn't be on PlayChale, when its host won't.
	 * The games module calls it off and tells everyone in it why; it's recorded against the staff
	 * member who did it.
	 */
	@Transactional
	public void takeDown(UUID by, UUID gameId, String why) {
		staff.require(by);
		moderation.takeDown(gameId, why, by);
		happened.record("admin.took-down-game", "game", gameId, by, Map.of("why", why == null ? "" : why.strip()));
	}

	/**
	 * A venue, as staff check it: where it is (to look it up on a map), how to reach it, what it
	 * charges, and who listed it. Its phone is the venue's own, given to show players who book.
	 *
	 * @param review  "pending" (waiting for staff), "approved" (live) or "declined"
	 * @param pitches each "name · sport format · price per hour", in its currency's minor units
	 */
	public record VenueRow(UUID id, String name, String area, String country, String currency, String address, String mapUrl, Double latitude,
			Double longitude, String phone, String description, List<String> amenities, UUID ownerId, String ownerName, boolean ownerGuest,
			String review, String reviewNote, Instant reviewedAt, Instant createdAt, long bookings, List<VenuePitch> pitches) {
	}

	public record VenuePitch(String name, String sport, String format, long pricePerHour) {
	}

	/** Which venues, and in what order: waiting (oldest first, the next to check), live, or declined. */
	private static final Map<String, String[]> VENUES_WHEN = Map.of(
			"waiting", new String[] { "v.review = 'pending'", "v.created_at" },
			"live", new String[] { "v.review = 'approved'", "v.name" },
			"declined", new String[] { "v.review = 'declined'", "v.reviewed_at DESC" });

	/** Venues waiting for staff to check them, live, or declined; found by name, area or who listed them. */
	@Transactional(readOnly = true)
	public List<VenueRow> venues(UUID by, String when, String query, int limit) {
		staff.require(by);
		var which = VENUES_WHEN.getOrDefault(when == null ? "waiting" : when, VENUES_WHEN.get("waiting"));
		var term = query == null ? "" : query.strip();
		var rows = jdbc.sql("""
				SELECT v.id, v.name, v.area, v.country, v.currency, v.address, v.map_url, v.latitude, v.longitude, v.phone, v.description,
				       v.owner_id, u.name AS owner_name, coalesce(u.guest, false) AS owner_guest, v.review, v.review_note, v.reviewed_at,
				       v.created_at, (SELECT count(*) FROM bookings b WHERE b.venue_id = v.id) AS bookings
				FROM venues v LEFT JOIN users u ON u.id = v.owner_id
				WHERE (CAST(:term AS text) = '' OR v.name ILIKE '%' || CAST(:term AS text) || '%' OR v.area ILIKE '%' || CAST(:term AS text) || '%'
				       OR u.name ILIKE '%' || CAST(:term AS text) || '%')
				""" + "  AND (" + which[0] + ")\nORDER BY " + which[1] + "\nLIMIT :limit")
			.param("term", term).param("limit", Math.clamp(limit <= 0 ? 50 : limit, 1, 200)).query().listOfRows();
		var ids = rows.stream().map(r -> (UUID) r.get("id")).toList();
		var pitches = new HashMap<UUID, List<VenuePitch>>();
		var amenities = new HashMap<UUID, List<String>>();
		if (!ids.isEmpty()) {
			jdbc.sql("SELECT venue_id, name, sport, format, price_per_hour FROM pitches WHERE venue_id IN (:ids) AND removed_at IS NULL ORDER BY position")
				.param("ids", ids).query().listOfRows()
				.forEach(p -> pitches.computeIfAbsent((UUID) p.get("venue_id"), k -> new ArrayList<>())
					.add(new VenuePitch((String) p.get("name"), (String) p.get("sport"), (String) p.get("format"), ((Number) p.get("price_per_hour")).longValue())));
			jdbc.sql("SELECT id, array_to_string(amenities, ',') AS amenities FROM venues WHERE id IN (:ids)").param("ids", ids).query().listOfRows()
				.forEach(a -> amenities.put((UUID) a.get("id"), ((String) a.get("amenities")).isEmpty() ? List.of() : List.of(((String) a.get("amenities")).split(","))));
		}
		return rows.stream().map(r -> {
			var id = (UUID) r.get("id");
			return new VenueRow(id, (String) r.get("name"), (String) r.get("area"), (String) r.get("country"), (String) r.get("currency"),
					(String) r.get("address"), (String) r.get("map_url"), r.get("latitude") == null ? null : ((Number) r.get("latitude")).doubleValue(),
					r.get("longitude") == null ? null : ((Number) r.get("longitude")).doubleValue(), (String) r.get("phone"), (String) r.get("description"),
					amenities.getOrDefault(id, List.of()), (UUID) r.get("owner_id"), (String) r.get("owner_name"), (Boolean) r.get("owner_guest"),
					(String) r.get("review"), (String) r.get("review_note"), instant(r.get("reviewed_at")), instant(r.get("created_at")),
					((Number) r.get("bookings")).longValue(), pitches.getOrDefault(id, List.of()));
		}).toList();
	}

	/** What's waiting for staff: venues to check, and reported things to decide on. The desk shows them on its tabs. */
	public record Waiting(long venues, long reports) {
	}

	@Transactional(readOnly = true)
	public Waiting waiting(UUID by) {
		staff.require(by);
		return jdbc.sql("""
				SELECT (SELECT count(*) FROM venues WHERE review = 'pending') AS venues,
				       (SELECT count(DISTINCT (subject_type, subject_id)) FROM reports WHERE status = 'open') AS reports
				""").query(Waiting.class).single();
	}

	/** Staff checked a venue and it's real: live for players, and its owner told. */
	@Transactional
	public void approveVenue(UUID by, UUID venueId) {
		staff.require(by);
		venueReview.approve(venueId, by);
		happened.record("admin.approved-venue", "venue", venueId, by, Map.of());
	}

	/** Not yet, or not any more: hidden from players, and its owner told what to change. */
	@Transactional
	public void declineVenue(UUID by, UUID venueId, String why) {
		staff.require(by);
		venueReview.decline(venueId, why, by);
		happened.record("admin.declined-venue", "venue", venueId, by, Map.of("why", why == null ? "" : why.strip()));
	}

	/**
	 * Something players reported, with every report about it: one decision answers them all.
	 *
	 * @param subjectType "game", "message", "user" or "venue"
	 * @param title       what it is: a game's title, what was said, a person's name, a venue's name;
	 *                    null when it's gone already (a host removed the message, say)
	 * @param detail      who and where: the host, who said it and in which game, a handle, an area
	 * @param gameId      for a message, the game it was said in (to open it)
	 * @param state       a game's or venue's status now ("cancelled", "declined"), to see it's dealt with
	 * @param outcome     for decided ones: "actioned" or "dismissed", with the note and who decided
	 */
	public record Reported(String subjectType, UUID subjectId, String title, String detail, UUID gameId, String state, List<ReportLine> reports,
			String outcome, String outcomeNote, String decidedBy, Instant decidedAt) {
	}

	/** One report: why, in the reporter's words, and who. */
	public record ReportLine(String reason, String note, UUID reporterId, String reporterName, Instant at) {
	}

	/**
	 * Reported things: open ones, the most reported first (then the oldest); or, with {@code decided},
	 * the latest decisions.
	 */
	@Transactional(readOnly = true)
	public List<Reported> reports(UUID by, boolean decided) {
		staff.require(by);
		var lines = jdbc.sql("""
				SELECT r.subject_type, r.subject_id, r.reason, r.note, r.reporter_id, u.name AS reporter_name, r.created_at,
				       r.status, r.outcome_note, d.name AS decided_by, r.decided_at
				FROM reports r JOIN users u ON u.id = r.reporter_id LEFT JOIN users d ON d.id = r.decided_by
				WHERE (r.status = 'open') <> :decided
				  AND (NOT :decided OR r.decided_at >= :recent)
				ORDER BY r.created_at
				""").param("decided", decided).param("recent", clock.instant().minus(Duration.ofDays(90)).atOffset(ZoneOffset.UTC))
			.query().listOfRows();
		// One per thing (and, decided, per decision), in the order its reports came.
		var grouped = new LinkedHashMap<String, List<Map<String, Object>>>();
		for (var line : lines) {
			var key = line.get("subject_type") + ":" + line.get("subject_id") + (decided ? ":" + line.get("decided_at") : "");
			grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(line);
		}
		var summaries = summaries(grouped.values().stream().map(g -> g.getFirst()).toList());
		var out = new ArrayList<Reported>();
		for (var group : grouped.values()) {
			var first = group.getFirst();
			var type = (String) first.get("subject_type");
			var id = (UUID) first.get("subject_id");
			var summary = summaries.get(type + ":" + id);
			out.add(new Reported(type, id, summary == null ? null : summary.title(), summary == null ? null : summary.detail(),
					summary == null ? null : summary.gameId(), summary == null ? null : summary.state(),
					group.stream().map(l -> new ReportLine((String) l.get("reason"), (String) l.get("note"), (UUID) l.get("reporter_id"),
							(String) l.get("reporter_name"), instant(l.get("created_at")))).toList(),
					decided ? (String) first.get("status") : null, decided ? (String) first.get("outcome_note") : null,
					decided ? (String) first.get("decided_by") : null, decided ? instant(first.get("decided_at")) : null));
		}
		if (decided) {
			out.sort(Comparator.comparing(Reported::decidedAt, Comparator.nullsLast(Comparator.reverseOrder())));
			return out.stream().limit(50).toList();
		}
		out.sort(Comparator.comparingInt((Reported r) -> -r.reports().size()).thenComparing(r -> r.reports().getFirst().at()));
		return out;
	}

	private record Summary(String title, String detail, UUID gameId, String state) {
	}

	/** What each reported thing is now, read from its own table, keyed "type:id". Gone ones are missing. */
	private Map<String, Summary> summaries(List<Map<String, Object>> things) {
		var byType = new HashMap<String, List<UUID>>();
		things.forEach(t -> byType.computeIfAbsent((String) t.get("subject_type"), k -> new ArrayList<>()).add((UUID) t.get("subject_id")));
		var out = new HashMap<String, Summary>();
		var queries = Map.of(
				"game", """
						SELECT g.id, g.title, 'Hosted by ' || coalesce(u.name, 'someone who’s left') || ' · ' || g.venue_name AS detail,
						       g.id AS game_id, g.status AS state
						FROM games g LEFT JOIN users u ON u.id = g.host_id WHERE g.id IN (:ids)
						""",
				"message", """
						SELECT m.id, m.body AS title, coalesce(u.name, 'Someone') || ' in ' || g.title AS detail, g.id AS game_id, NULL AS state
						FROM game_messages m JOIN games g ON g.id = m.game_id LEFT JOIN users u ON u.id = m.user_id WHERE m.id IN (:ids)
						""",
				"user", """
						SELECT u.id, u.name AS title, coalesce('@' || u.handle, 'No username') || ' · ' || coalesce(u.area, u.country) AS detail,
						       NULL::uuid AS game_id, CASE WHEN u.deleted_at IS NOT NULL THEN 'closed' END AS state
						FROM users u WHERE u.id IN (:ids)
						""",
				"venue", """
						SELECT v.id, v.name AS title, v.area || ' · listed by ' || coalesce(u.name, 'someone who’s left') AS detail, NULL::uuid AS game_id,
						       v.review AS state
						FROM venues v LEFT JOIN users u ON u.id = v.owner_id WHERE v.id IN (:ids)
						""");
		byType.forEach((type, ids) -> jdbc.sql(queries.get(type)).param("ids", ids).query().listOfRows()
			.forEach(r -> out.put(type + ":" + r.get("id"), new Summary((String) r.get("title"), (String) r.get("detail"), (UUID) r.get("game_id"),
					(String) r.get("state")))));
		return out;
	}

	/**
	 * Takes the reported thing down with a reason (the game called off, the message removed, the venue
	 * taken off) and closes its reports. A person can't be taken down from here yet; for them, and for
	 * something already gone, the reports are closed as dealt with.
	 */
	@Transactional
	public void takeDownReported(UUID by, String subjectType, UUID subjectId, String why) {
		staff.require(by);
		var reason = why == null ? "" : why.strip();
		if (reason.isEmpty()) {
			throw BusinessException.invalid("Say why: it’s recorded, and for a game or venue its people read it.");
		}
		switch (subjectType) {
			case "game" -> {
				if (gameOpen(subjectId)) {
					takeDown(by, subjectId, reason);
				}
			}
			case "message" -> {
				if (jdbc.sql("SELECT count(*) > 0 FROM game_messages WHERE id = :id").param("id", subjectId).query(Boolean.class).single()) {
					removeMessage(by, subjectId, reason);
				}
			}
			case "venue" -> {
				if (jdbc.sql("SELECT count(*) > 0 FROM venues WHERE id = :id AND review <> 'declined'").param("id", subjectId).query(Boolean.class).single()) {
					declineVenue(by, subjectId, reason);
				}
			}
			case "user" -> {
				// Nothing to take down yet: what was done (a call, a warning) is the note.
			}
			default -> throw BusinessException.invalid("That isn’t something players can report.");
		}
		reportDecisions.actioned(subjectType, subjectId, reason, by);
		happened.record("admin.decided-report", subjectType, subjectId, by, Map.of("outcome", "actioned"));
	}

	/** Nothing wrong with it: its reports are closed and it stays as it is. */
	@Transactional
	public void dismissReports(UUID by, String subjectType, UUID subjectId, String why) {
		staff.require(by);
		reportDecisions.dismissed(subjectType, subjectId, why, by);
		happened.record("admin.decided-report", subjectType, subjectId, by, Map.of("outcome", "dismissed"));
	}

	/** Still on and not yet played: a game that can be called off. */
	private boolean gameOpen(UUID gameId) {
		return jdbc.sql("SELECT count(*) > 0 FROM games WHERE id = :id AND status NOT IN ('cancelled', 'completed')").param("id", gameId)
			.query(Boolean.class).single();
	}

	private static Instant instant(Object value) {
		return switch (value) {
			case null -> null;
			case Timestamp t -> t.toInstant();
			case OffsetDateTime o -> o.toInstant();
			case Instant i -> i;
			default -> throw new IllegalStateException("Not a time: " + value.getClass());
		};
	}

	/**
	 * Everything held about one person, for a data request. Deletion already worked; this is the
	 * other half someone is entitled to ask for, and asking for it is itself recorded.
	 */
	@Transactional(readOnly = true)
	public Map<String, Object> export(UUID by, UUID userId) {
		staff.require(by);
		happened.record("admin.exported-person", "user", userId, by, Map.of());
		return Map.ofEntries(
				Map.entry("user", one("SELECT * FROM users WHERE id = :id", userId)),
				Map.entry("games", rows("SELECT g.* FROM games g JOIN game_participants p ON p.game_id = g.id WHERE p.user_id = :id", userId)),
				Map.entry("spots", rows("SELECT * FROM game_participants WHERE user_id = :id", userId)),
				Map.entry("departures", rows("SELECT * FROM game_departures WHERE user_id = :id", userId)),
				Map.entry("messages", rows("SELECT * FROM game_messages WHERE user_id = :id", userId)),
				Map.entry("series", rows("SELECT * FROM game_series WHERE host_id = :id", userId)),
				Map.entry("seriesOptOuts", rows("SELECT * FROM game_series_optouts WHERE user_id = :id", userId)),
				Map.entry("payments", rows("SELECT * FROM payments WHERE user_id = :id", userId)),
				Map.entry("movements", rows("SELECT * FROM movements WHERE user_id = :id", userId)),
				Map.entry("notifications", rows("SELECT * FROM notifications WHERE user_id = :id", userId)),
				Map.entry("results", rows("SELECT * FROM result_players WHERE user_id = :id", userId)),
				Map.entry("reportsMade", rows("SELECT subject_type, reason, note, status, created_at FROM reports WHERE reporter_id = :id", userId)));
	}

	private List<Map<String, Object>> rows(String sql, UUID userId) {
		return jdbc.sql(sql).param("id", userId).query().listOfRows();
	}

	private Map<String, Object> one(String sql, UUID userId) {
		var found = jdbc.sql(sql).param("id", userId).query().listOfRows();
		return found.isEmpty() ? Map.of() : found.get(0);
	}

}
