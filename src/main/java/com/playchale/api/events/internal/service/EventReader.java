package com.playchale.api.events.internal.service;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.playchale.api.events.internal.service.EventAccess.EventRow;
import com.playchale.api.organisations.api.OrganisationAccess;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.shared.maps.Pin;
import com.playchale.api.users.api.UserDirectory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Puts an event together for its screens: every group, person, game and entry in one answer. */
@Component
class EventReader {

	private final JdbcClient jdbc;

	private final OrganisationAccess organisations;

	private final UserDirectory users;

	private final EventAccess access;

	private final EventStandings standings;

	EventReader(JdbcClient jdbc, OrganisationAccess organisations, UserDirectory users, EventAccess access, EventStandings standings) {
		this.jdbc = jdbc;
		this.organisations = organisations;
		this.users = users;
		this.access = access;
		this.standings = standings;
	}

	@Transactional(readOnly = true)
	EventViews.Detail detail(UUID eventId, UUID viewerId) {
		var row = access.event(eventId);
		var role = access.role(row, viewerId);
		if (role == null) {
			throw BusinessException.notFound(EventAccess.GONE);
		}
		return build(row, viewerId, role);
	}

	/**
	 * The event behind a board link, for a screen nobody is signed in on: the overall table, how each
	 * game stands and who's up next, and the latest results.
	 */
	@Transactional(readOnly = true)
	EventViews.Board board(String token) {
		var eventId = jdbc.sql("SELECT id FROM events WHERE board_token = :token").param("token", token == null ? "" : token.strip())
			.query(UUID.class).optional()
			.orElseThrow(() -> BusinessException.notFound("That board link doesn’t work any more. Ask the organisers for the new one."));
		var detail = build(access.event(eventId), null, null);
		var latest = new ArrayList<EventViews.Latest>();
		var games = new ArrayList<EventViews.BoardGame>();
		for (var game : detail.games()) {
			var name = game.category() == null ? game.name() : game.name() + " · " + game.category();
			var places = game.places().stream().limit(3).map(e -> EventStandings.nameOf(game, e)).toList();
			var next = new ArrayList<String>();
			for (var m : game.matches()) {
				if (m.recordedAt() != null && m.homeEntryId() != null && m.awayEntryId() != null) {
					latest.add(new EventViews.Latest(name, EventPlayService.summary(game, m), m.recordedAt()));
				}
				else if (m.recordedAt() == null && m.homeEntryId() != null && m.awayEntryId() != null && next.size() < 2) {
					next.add(EventStandings.nameOf(game, m.homeEntryId()) + " v " + EventStandings.nameOf(game, m.awayEntryId()));
				}
			}
			for (var h : game.heats()) {
				if (h.recordedAt() != null) {
					latest.add(new EventViews.Latest(name, EventPlayService.summary(game, h), h.recordedAt()));
				}
				else if (next.size() < 2) {
					next.add(("final".equals(h.stage()) ? "Final" : "Heat " + h.number()) + ": "
							+ h.lanes().stream().map(l -> EventStandings.nameOf(game, l.entryId())).collect(java.util.stream.Collectors.joining(", ")));
				}
			}
			games.add(new EventViews.BoardGame(game.id(), game.discipline(), name, game.category(), game.status(), game.location(),
					game.startsAt(), places, next));
		}
		latest.sort(java.util.Comparator.comparing(EventViews.Latest::at).reversed());
		var info = detail.event();
		return new EventViews.Board(info.name(), info.timezone(), detail.organisation(), info.startsOn(), info.endsOn(), info.venue(), info.status(),
				detail.groups(), detail.table(), games, latest.stream().limit(8).toList(), java.time.Instant.now());
	}

	/** The whole event. {@code role} null for the board, which shows no viewer and no links. */
	private EventViews.Detail build(EventRow row, UUID viewerId, String role) {
		var eventId = row.id();
		var info = info(eventId);
		var groups = groups(eventId);
		var linked = new HashSet<UUID>();
		var people = jdbc.sql("""
				SELECT id, display_name, group_id, user_id, source FROM event_people
				WHERE event_id = :event ORDER BY lower(display_name), created_at
				""").param("event", eventId).query((rs, n) -> {
				var userId = (UUID) rs.getObject("user_id");
				if (userId != null) {
					linked.add(userId);
				}
				return new EventViews.Person((UUID) rs.getObject("id"), rs.getString("display_name"), (UUID) rs.getObject("group_id"),
						userId, null, rs.getString("source"));
			}).list();

		var coordinatorRows = jdbc.sql("""
				SELECT c.game_id, c.user_id FROM event_game_coordinators c JOIN event_games g ON g.id = c.game_id
				WHERE g.event_id = :event ORDER BY c.created_at
				""").param("event", eventId).query((rs, n) -> new UUID[] { (UUID) rs.getObject(1), (UUID) rs.getObject(2) }).list();
		coordinatorRows.forEach(r -> linked.add(r[1]));
		var accounts = users.findAll(linked);
		people = people.stream().map(p -> p.userId() == null || !accounts.containsKey(p.userId()) ? p
				: new EventViews.Person(p.id(), p.name(), p.groupId(), p.userId(), accounts.get(p.userId()).avatar(), p.source())).toList();

		var coordinators = new HashMap<UUID, List<EventViews.Coordinator>>();
		var mine = new ArrayList<UUID>();
		for (var r : coordinatorRows) {
			var account = accounts.get(r[1]);
			coordinators.computeIfAbsent(r[0], k -> new ArrayList<>()).add(new EventViews.Coordinator(r[1],
					account == null ? "Former member" : account.name(), account == null ? null : account.avatar()));
			if (r[1].equals(viewerId) && viewerId != null) {
				mine.add(r[0]);
			}
		}

		var entryPeople = new HashMap<UUID, List<UUID>>();
		jdbc.sql("""
				SELECT ep.entry_id, ep.person_id FROM event_entry_people ep JOIN event_games g ON g.id = ep.game_id
				JOIN event_people p ON p.id = ep.person_id
				WHERE g.event_id = :event ORDER BY lower(p.display_name)
				""").param("event", eventId).query((rs, n) -> new UUID[] { (UUID) rs.getObject(1), (UUID) rs.getObject(2) }).list()
			.forEach(r -> entryPeople.computeIfAbsent(r[0], k -> new ArrayList<>()).add(r[1]));
		var entries = new HashMap<UUID, List<EventViews.Entry>>();
		jdbc.sql("""
				SELECT e.id, e.game_id, e.name, e.group_id, e.seed, e.status FROM event_entries e JOIN event_games g ON g.id = e.game_id
				WHERE g.event_id = :event ORDER BY e.seed
				""").param("event", eventId).query((rs, n) -> {
				var id = (UUID) rs.getObject("id");
				entries.computeIfAbsent((UUID) rs.getObject("game_id"), k -> new ArrayList<>()).add(new EventViews.Entry(id,
						rs.getString("name"), (UUID) rs.getObject("group_id"), entryPeople.getOrDefault(id, List.of()), rs.getInt("seed"),
						rs.getString("status")));
				return id;
			}).list();
		var interest = new HashMap<UUID, List<UUID>>();
		jdbc.sql("""
				SELECT i.game_id, i.person_id FROM event_game_interest i JOIN event_games g ON g.id = i.game_id
				WHERE g.event_id = :event ORDER BY i.created_at
				""").param("event", eventId).query((rs, n) -> new UUID[] { (UUID) rs.getObject(1), (UUID) rs.getObject(2) }).list()
			.forEach(r -> interest.computeIfAbsent(r[0], k -> new ArrayList<>()).add(r[1]));

		var games = jdbc.sql("SELECT * FROM event_games WHERE event_id = :event ORDER BY position").param("event", eventId)
			.query((rs, n) -> {
				var id = (UUID) rs.getObject("id");
				return new EventViews.Game(id, rs.getString("discipline"), rs.getString("name"), rs.getString("category"),
						rs.getString("entry_kind"), (Integer) rs.getObject("team_size"), rs.getString("format"), rs.getString("scoring"),
						shortOrNull(rs, "best_of"), rs.getBoolean("draws_allowed"), rs.getBoolean("third_place"),
						shortOrNull(rs, "heat_size"), shortOrNull(rs, "advance_per_heat"), rs.getString("location"),
						instant(rs, "starts_at"), rs.getString("status"), rs.getInt("position"),
						coordinators.getOrDefault(id, List.of()), entries.getOrDefault(id, List.of()), interest.getOrDefault(id, List.of()),
						List.of(), List.of(), null, List.of());
			}).list();

		var play = standings.load(eventId);
		var played = games.stream().map(g -> standings.withPlay(g, play)).toList();
		var table = standings.groupTable(groups, info.placingPoints(), played);

		var admin = "admin".equals(role);
		var links = admin ? jdbc.sql("SELECT join_code, board_token FROM events WHERE id = :id").param("id", eventId)
			.query((rs, n) -> new EventViews.Links("/events/join?code=" + rs.getString(1), "/events/board/" + rs.getString(2))).single()
				: null;
		var viewer = role == null ? null : new EventViews.Viewer(role, access.personOf(eventId, viewerId), List.copyOf(mine));
		// The board shows names only: no accounts, no faces.
		var shownPeople = role == null ? List.<EventViews.Person>of() : people;
		return new EventViews.Detail(info, brand(row), viewer, groups, shownPeople, played, table, links);
	}

	EventViews.Info info(UUID eventId) {
		return jdbc.sql("SELECT * FROM events WHERE id = :id").param("id", eventId).query((rs, n) -> new EventViews.Info(
				(UUID) rs.getObject("id"), (UUID) rs.getObject("organisation_id"), rs.getString("name"),
				rs.getObject("starts_on", java.time.LocalDate.class), rs.getObject("ends_on", java.time.LocalDate.class),
				rs.getString("timezone"), rs.getString("country").strip(), venue(rs), rs.getString("status"),
				rs.getBoolean("registration_open"), points(rs.getArray("placing_points")), instant(rs, "created_at")))
			.optional().orElseThrow(() -> BusinessException.notFound(EventAccess.GONE));
	}

	List<EventViews.Group> groups(UUID eventId) {
		return jdbc.sql("SELECT id, name, colour, position FROM event_groups WHERE event_id = :event ORDER BY position, name")
			.param("event", eventId)
			.query((rs, n) -> new EventViews.Group((UUID) rs.getObject(1), rs.getString(2), rs.getString(3), rs.getInt(4))).list();
	}

	EventViews.Brand brand(EventRow event) {
		var brand = organisations.brand(event.organisationId());
		return brand == null ? null : new EventViews.Brand(brand.id(), brand.name(), brand.primaryColour(), brand.logoUrl());
	}

	/** Events in a workspace, newest first. For anyone with a seat in it. */
	@Transactional(readOnly = true)
	List<EventViews.Summary> inWorkspace(UUID organisationId, UUID viewerId) {
		var seat = organisations.roleOf(organisationId, viewerId)
			.orElseThrow(() -> BusinessException.notFound("That organisation doesn’t exist any more."));
		var role = "official".equals(seat) ? "staff" : "admin";
		return summaries("e.organisation_id = :scope", organisationId, role);
	}

	/**
	 * The events the signed-in player has a part in: through their workspace, a game they coordinate,
	 * or taking part themselves. Their role is worked out per event.
	 */
	@Transactional(readOnly = true)
	List<EventViews.Summary> mine(UUID userId) {
		return jdbc.sql("""
				SELECT DISTINCT e.id, e.starts_on FROM events e
				WHERE e.organisation_id IN (SELECT organisation_id FROM organisation_memberships WHERE user_id = :user)
				   OR EXISTS (SELECT 1 FROM event_people p WHERE p.event_id = e.id AND p.user_id = :user)
				   OR EXISTS (SELECT 1 FROM event_game_coordinators c JOIN event_games g ON g.id = c.game_id
				              WHERE g.event_id = e.id AND c.user_id = :user)
				ORDER BY e.starts_on DESC LIMIT 50
				""").param("user", userId).query((rs, n) -> (UUID) rs.getObject(1)).list().stream()
			.flatMap(id -> {
				var role = access.role(access.event(id), userId);
				return summaries("e.id = :scope", id, role == null ? "player" : role).stream();
			}).toList();
	}

	private List<EventViews.Summary> summaries(String where, UUID scope, String role) {
		return jdbc.sql("""
				SELECT e.id, e.organisation_id, o.name AS organisation_name, o.primary_colour, e.name, e.starts_on, e.ends_on,
				       e.venue_name, e.status,
				       (SELECT count(*) FROM event_games g WHERE g.event_id = e.id) AS games,
				       (SELECT count(*) FROM event_people p WHERE p.event_id = e.id) AS people
				FROM events e JOIN organisations o ON o.id = e.organisation_id
				WHERE %s ORDER BY e.starts_on DESC, e.created_at DESC
				""".formatted(where)).param("scope", scope).query((rs, n) -> new EventViews.Summary((UUID) rs.getObject("id"),
				(UUID) rs.getObject("organisation_id"), rs.getString("organisation_name"), rs.getString("primary_colour"),
				rs.getString("name"), rs.getObject("starts_on", java.time.LocalDate.class), rs.getObject("ends_on", java.time.LocalDate.class),
				rs.getString("venue_name"), rs.getString("status"), rs.getInt("games"), rs.getInt("people"), role)).list();
	}

	/** What someone opening a join link sees, and what they already have there. */
	@Transactional(readOnly = true)
	EventViews.JoinPreview preview(UUID eventId, UUID userId) {
		var row = access.event(eventId);
		var info = info(eventId);
		var games = jdbc.sql("SELECT id, discipline, name, category, entry_kind, status FROM event_games WHERE event_id = :event ORDER BY position")
			.param("event", eventId).query((rs, n) -> new EventViews.JoinGame((UUID) rs.getObject(1), rs.getString(2), rs.getString(3),
					rs.getString(4), rs.getString(5), rs.getString(6))).list();
		EventViews.Mine mine = null;
		var personId = access.personOf(eventId, userId);
		if (personId != null) {
			var groupId = jdbc.sql("SELECT group_id FROM event_people WHERE id = :id").param("id", personId)
				.query((rs, n) -> (UUID) rs.getObject(1)).single();
			mine = new EventViews.Mine(personId, groupId, gamesOf(personId));
		}
		return new EventViews.JoinPreview(info.id(), info.name(), brand(row), info.startsOn(), info.endsOn(), info.venue(), info.status(),
				info.registrationOpen(), groups(eventId), games, mine);
	}

	/** The games someone is in, or has asked to be in. */
	List<UUID> gamesOf(UUID personId) {
		return jdbc.sql("""
				SELECT g.id FROM event_games g WHERE g.id IN (
				  SELECT game_id FROM event_entry_people WHERE person_id = :person
				  UNION SELECT game_id FROM event_game_interest WHERE person_id = :person)
				ORDER BY g.position
				""").param("person", personId).query(UUID.class).list();
	}

	private static EventViews.Venue venue(ResultSet rs) throws SQLException {
		var name = rs.getString("venue_name");
		var pin = pin(rs);
		return name == null ? null : new EventViews.Venue(name, rs.getString("venue_area"), rs.getString("map_url"), pin == null ? null : pin.view());
	}

	/** Where the event is on the map, as kept: an edit that leaves it alone keeps its age. */
	Pin pin(UUID eventId) {
		return jdbc.sql("SELECT latitude, longitude, place_id, pin_source, pinned_at FROM events WHERE id = :id").param("id", eventId)
			.query((rs, n) -> pin(rs)).optional().orElse(null);
	}

	private static Pin pin(ResultSet rs) throws SQLException {
		var source = rs.getString("pin_source");
		if (source == null) {
			return null;
		}
		var pinnedAt = rs.getObject("pinned_at", OffsetDateTime.class);
		return new Pin(rs.getObject("latitude", Double.class), rs.getObject("longitude", Double.class), rs.getString("place_id"), source,
				pinnedAt == null ? null : pinnedAt.toInstant());
	}

	private static List<Integer> points(Array array) throws SQLException {
		return array == null ? List.of() : Arrays.stream((Integer[]) array.getArray()).toList();
	}

	private static Integer shortOrNull(ResultSet rs, String column) throws SQLException {
		var value = rs.getObject(column);
		return value == null ? null : ((Number) value).intValue();
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		var value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	static Map<UUID, String> namesOf(JdbcClient jdbc, List<UUID> personIds) {
		var names = new HashMap<UUID, String>();
		if (!personIds.isEmpty()) {
			jdbc.sql("SELECT id, display_name FROM event_people WHERE id IN (:ids)").param("ids", personIds)
				.query((rs, n) -> names.put((UUID) rs.getObject(1), rs.getString(2))).list();
		}
		return names;
	}

}
