package com.playchale.api.events.internal.service;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.playchale.api.organisations.api.OrganisationAccess;
import com.playchale.api.shared.error.BusinessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Who may do what in an event.
 *
 * <ul>
 * <li>The workspace's owner and admins run it: everything.</li>
 * <li>A coordinator runs the games they're given: their entries, draw and results, nothing else.</li>
 * <li>A group's rep (someone from the company, or the house) registers that group's people and enters
 * them in games, while sign-ups are open and before each game's draw. Nothing else.</li>
 * <li>Anyone else in the workspace, and anyone taking part with their own account, can look.</li>
 * </ul>
 *
 * <p>Refusals say "not found" rather than "forbidden", as the corporate screens do: someone trying
 * other events' ids learns nothing from the difference.
 */
@Component
class EventAccess {

	static final String GONE = "That event doesn’t exist any more.";

	private final JdbcClient jdbc;

	private final OrganisationAccess organisations;

	EventAccess(JdbcClient jdbc, OrganisationAccess organisations) {
		this.jdbc = jdbc;
		this.organisations = organisations;
	}

	/** The facts about an event that the rules turn on. */
	record EventRow(UUID id, UUID organisationId, String name, String status, boolean registrationOpen, String timezone) {

		boolean open() {
			return "open".equals(status);
		}

	}

	/** The facts about a game that the rules turn on. */
	record GameRow(UUID id, UUID eventId, String name, String entryKind, String format, String scoring, String status, Integer teamSize) {

		boolean open() {
			return "open".equals(status);
		}

	}

	EventRow event(UUID eventId) {
		return jdbc.sql("SELECT id, organisation_id, name, status, registration_open, timezone FROM events WHERE id = :id")
			.param("id", eventId)
			.query((rs, n) -> new EventRow((UUID) rs.getObject(1), (UUID) rs.getObject(2), rs.getString(3), rs.getString(4),
					rs.getBoolean(5), rs.getString(6)))
			.optional().orElseThrow(() -> BusinessException.notFound(GONE));
	}

	GameRow game(UUID eventId, UUID gameId) {
		return jdbc.sql("""
				SELECT id, event_id, name, entry_kind, format, scoring, status, team_size FROM event_games
				WHERE id = :id AND event_id = :event
				""").param("id", gameId).param("event", eventId)
			.query((rs, n) -> new GameRow((UUID) rs.getObject(1), (UUID) rs.getObject(2), rs.getString(3), rs.getString(4),
					rs.getString(5), rs.getString(6), rs.getString(7), (Integer) rs.getObject(8)))
			.optional().orElseThrow(() -> BusinessException.notFound("That game isn’t in this event any more."));
	}

	/** admin, staff, rep, player, or null for someone with no part in it. */
	String role(EventRow event, UUID userId) {
		var seat = organisations.roleOf(event.organisationId(), userId);
		if (seat.isPresent()) {
			return "official".equals(seat.get()) ? "staff" : "admin";
		}
		if (!represented(event.id(), userId).isEmpty()) {
			return "rep";
		}
		return personOf(event.id(), userId) != null ? "player" : null;
	}

	/** The groups in this event they're a rep for. */
	List<UUID> represented(UUID eventId, UUID userId) {
		if (userId == null) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT r.group_id FROM event_group_reps r JOIN event_groups g ON g.id = r.group_id
				WHERE g.event_id = :event AND r.user_id = :user
				""").param("event", eventId).param("user", userId).query(UUID.class).list();
	}

	/**
	 * Someone changing a group's people or entries: an admin always; a rep only for the groups they
	 * represent, and only while the event takes sign-ups. {@code groups} are every group the change
	 * touches; a rep can't touch someone in no group.
	 *
	 * @return whether they're doing it as an admin
	 */
	boolean requireAdminOrRep(EventRow event, UUID userId, Collection<UUID> groups) {
		if (organisations.isAdmin(event.organisationId(), userId)) {
			return true;
		}
		var mine = represented(event.id(), userId);
		if (mine.isEmpty()) {
			throw BusinessException.notFound(GONE);
		}
		if (groups.isEmpty() || groups.stream().anyMatch(g -> g == null || !mine.contains(g))) {
			throw BusinessException.conflict("You can only change your own group’s people and entries.");
		}
		requireSignUps(event);
		return false;
	}

	/** A rep's changes wait for the organisers once sign-ups close, or the event is over. */
	static void requireSignUps(EventRow event) {
		if (!event.open()) {
			throw BusinessException.conflict("This event is over, so nothing can change now.");
		}
		if (!event.registrationOpen()) {
			throw BusinessException.conflict("Sign-ups are closed. Ask the organisers to make changes.");
		}
	}

	/**
	 * A game's entries changing: an admin or the game's coordinator; or a rep, for every group the
	 * entry touches, while sign-ups are open and before the draw.
	 */
	GameRow requireEntrant(UUID eventId, UUID gameId, UUID userId, Collection<UUID> groups) {
		var event = event(eventId);
		var game = game(eventId, gameId);
		if (organisations.isAdmin(event.organisationId(), userId) || coordinates(gameId, userId)) {
			return game;
		}
		requireAdminOrRep(event, userId, groups);
		EventEntries.requireOpen(game);
		return game;
	}

	/** Their place in the event, if they joined it with their account. */
	UUID personOf(UUID eventId, UUID userId) {
		return jdbc.sql("SELECT id FROM event_people WHERE event_id = :event AND user_id = :user")
			.param("event", eventId).param("user", userId).query(UUID.class).optional().orElse(null);
	}

	EventRow requireViewer(UUID eventId, UUID userId) {
		var event = event(eventId);
		if (role(event, userId) == null) {
			throw BusinessException.notFound(GONE);
		}
		return event;
	}

	EventRow requireAdmin(UUID eventId, UUID userId) {
		var event = event(eventId);
		if (!organisations.isAdmin(event.organisationId(), userId)) {
			throw BusinessException.notFound(GONE);
		}
		return event;
	}

	boolean isAdmin(EventRow event, UUID userId) {
		return organisations.isAdmin(event.organisationId(), userId);
	}

	/** An admin, or this game's coordinator. */
	GameRow requireRunner(UUID eventId, UUID gameId, UUID userId) {
		var event = event(eventId);
		var game = game(eventId, gameId);
		if (!organisations.isAdmin(event.organisationId(), userId) && !coordinates(gameId, userId)) {
			throw BusinessException.notFound(GONE);
		}
		return game;
	}

	boolean coordinates(UUID gameId, UUID userId) {
		return jdbc.sql("SELECT count(*) FROM event_game_coordinators WHERE game_id = :game AND user_id = :user")
			.param("game", gameId).param("user", userId).query(Integer.class).single() > 0;
	}

	boolean inWorkspace(EventRow event, UUID userId) {
		return organisations.roleOf(event.organisationId(), userId).isPresent();
	}

}
