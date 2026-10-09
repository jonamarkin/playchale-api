package com.playchale.api.events.internal.service;

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

	/** admin, staff, player, or null for someone with no part in it. */
	String role(EventRow event, UUID userId) {
		var seat = organisations.roleOf(event.organisationId(), userId);
		if (seat.isPresent()) {
			return "official".equals(seat.get()) ? "staff" : "admin";
		}
		return personOf(event.id(), userId) != null ? "player" : null;
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
