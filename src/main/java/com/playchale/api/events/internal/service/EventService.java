package com.playchale.api.events.internal.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.playchale.api.events.internal.domain.GameSettings;
import com.playchale.api.market.Market;
import com.playchale.api.organisations.api.OrganisationAccess;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.shared.events.Happened;
import com.playchale.api.shared.maps.MapLink;
import com.playchale.api.users.api.UserDirectory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An event and the people in it: setting it up, its groups, adding names, and the link people join
 * with. The games are {@link EventGameService}'s.
 */
@Service
public class EventService {

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final Pattern COLOUR = Pattern.compile("^#[0-9a-fA-F]{6}$");

	/** Colours for groups the admin didn't pick one for: distinct on a projector and on a phone. */
	static final List<String> PALETTE = List.of("#1f6feb", "#d1342f", "#2f9e44", "#e8a400", "#7c3aed", "#f2701d", "#0f9db5",
			"#d6336c");

	private static final int MAX_GROUPS = 24;

	private static final int MAX_PEOPLE = 3000;

	private final JdbcClient jdbc;

	private final Clock clock;

	private final EventAccess access;

	private final EventReader reader;

	private final EventEntries entries;

	private final OrganisationAccess organisations;

	private final UserDirectory users;

	private final Happened happened;

	EventService(JdbcClient jdbc, Clock clock, EventAccess access, EventReader reader, EventEntries entries,
			OrganisationAccess organisations, UserDirectory users, Happened happened) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.access = access;
		this.reader = reader;
		this.entries = entries;
		this.organisations = organisations;
		this.users = users;
		this.happened = happened;
	}

	public record VenueInput(String name, String area, String mapUrl) {
	}

	public record GroupInput(String name, String colour) {
	}

	/** An event's details. Groups are only read when it's created; after that they have their own calls. */
	public record EventInput(String name, LocalDate startsOn, LocalDate endsOn, String timezone, String country, VenueInput venue,
			List<GroupInput> groups, List<Integer> placingPoints, Boolean registrationOpen) {
	}

	public record PersonInput(String name, UUID groupId) {
	}

	/* ------------------------------------------------------------------ */
	/* Reading                                                              */
	/* ------------------------------------------------------------------ */

	public EventViews.Detail get(UUID eventId, UUID userId) {
		return reader.detail(eventId, userId);
	}

	public List<EventViews.Summary> inWorkspace(UUID organisationId, UUID userId) {
		return reader.inWorkspace(organisationId, userId);
	}

	public List<EventViews.Summary> mine(UUID userId) {
		return reader.mine(userId);
	}

	/* ------------------------------------------------------------------ */
	/* The event                                                            */
	/* ------------------------------------------------------------------ */

	@Transactional
	public EventViews.Detail create(UUID organisationId, EventInput input, UUID userId) {
		organisations.requireAdmin(organisationId, userId);
		var details = details(input, true, new Integer[] { 5, 3, 1 });
		var groups = input.groups() == null ? List.<GroupInput>of() : input.groups();
		if (groups.size() > MAX_GROUPS) {
			throw BusinessException.invalid("An event can have up to %d groups.".formatted(MAX_GROUPS));
		}
		var id = UUID.randomUUID();
		var now = now();
		jdbc.sql("""
				INSERT INTO events (id, organisation_id, name, starts_on, ends_on, timezone, country, venue_name, venue_area, map_url,
				                    status, registration_open, join_code, board_token, placing_points, created_by, created_at, updated_at)
				VALUES (:id, :organisation, :name, :startsOn, :endsOn, :timezone, :country, :venueName, :venueArea, :mapUrl,
				        'open', :registration, :code, :board, :points, :user, :now, :now)
				""").param("id", id).param("organisation", organisationId).param("name", details.name())
			.param("startsOn", details.startsOn()).param("endsOn", details.endsOn()).param("timezone", details.timezone())
			.param("country", details.country()).param("venueName", details.venueName()).param("venueArea", details.venueArea())
			.param("mapUrl", details.mapUrl()).param("registration", details.registrationOpen()).param("code", code(9))
			.param("board", code(18)).param("points", details.points()).param("user", userId).param("now", now).update();
		var seen = new HashSet<String>();
		for (var group : groups) {
			var name = groupName(group.name());
			if (!seen.add(name.toLowerCase(Locale.ROOT))) {
				throw BusinessException.invalid("Two groups are called %s. Give each its own name.".formatted(name));
			}
			insertGroup(id, name, colour(group.colour(), seen.size() - 1), seen.size() - 1);
		}
		happened.record("event.created", "event", id, userId, organisationId, null, Map.of("groups", groups.size()));
		return reader.detail(id, userId);
	}

	@Transactional
	public EventViews.Detail update(UUID eventId, EventInput input, UUID userId) {
		var event = access.requireAdmin(eventId, userId);
		// Whatever wasn't sent stays as it was.
		var points = jdbc.sql("SELECT placing_points FROM events WHERE id = :id").param("id", eventId)
			.query((rs, n) -> (Integer[]) rs.getArray(1).getArray()).single();
		var details = details(input, event.registrationOpen(), points);
		jdbc.sql("""
				UPDATE events SET name = :name, starts_on = :startsOn, ends_on = :endsOn, timezone = :timezone, country = :country,
				       venue_name = :venueName, venue_area = :venueArea, map_url = :mapUrl, registration_open = :registration,
				       placing_points = :points, updated_at = :now
				WHERE id = :id
				""").param("name", details.name()).param("startsOn", details.startsOn()).param("endsOn", details.endsOn())
			.param("timezone", details.timezone()).param("country", details.country()).param("venueName", details.venueName())
			.param("venueArea", details.venueArea()).param("mapUrl", details.mapUrl()).param("registration", details.registrationOpen())
			.param("points", details.points()).param("now", now()).param("id", eventId).update();
		happened.record("event.updated", "event", eventId, userId, event.organisationId(), null, Map.of());
		return reader.detail(eventId, userId);
	}

	/** Calls the whole event off. Everything in it stays, to look back on. */
	@Transactional
	public EventViews.Detail cancel(UUID eventId, UUID userId) {
		var event = access.requireAdmin(eventId, userId);
		if (!event.open()) {
			throw BusinessException.conflict("This event is already over.");
		}
		jdbc.sql("UPDATE events SET status = 'cancelled', registration_open = false, updated_at = :now WHERE id = :id")
			.param("now", now()).param("id", eventId).update();
		happened.record("event.cancelled", "event", eventId, userId, event.organisationId(), null, Map.of());
		return reader.detail(eventId, userId);
	}

	/** A new join link. The old one stops working at once. */
	@Transactional
	public EventViews.Detail newJoinLink(UUID eventId, UUID userId) {
		var event = access.requireAdmin(eventId, userId);
		jdbc.sql("UPDATE events SET join_code = :code, updated_at = :now WHERE id = :id").param("code", code(9)).param("now", now())
			.param("id", eventId).update();
		happened.record("event.join-link-changed", "event", eventId, userId, event.organisationId(), null, Map.of());
		return reader.detail(eventId, userId);
	}

	/** A new board link, for when the old one has gone further than the screen it was for. */
	@Transactional
	public EventViews.Detail newBoardLink(UUID eventId, UUID userId) {
		var event = access.requireAdmin(eventId, userId);
		jdbc.sql("UPDATE events SET board_token = :token, updated_at = :now WHERE id = :id").param("token", code(18))
			.param("now", now()).param("id", eventId).update();
		happened.record("event.board-link-changed", "event", eventId, userId, event.organisationId(), null, Map.of());
		return reader.detail(eventId, userId);
	}

	/* ------------------------------------------------------------------ */
	/* Groups                                                               */
	/* ------------------------------------------------------------------ */

	@Transactional
	public EventViews.Detail addGroup(UUID eventId, GroupInput input, UUID userId) {
		access.requireAdmin(eventId, userId);
		var count = jdbc.sql("SELECT count(*) FROM event_groups WHERE event_id = :event").param("event", eventId)
			.query(Integer.class).single();
		if (count >= MAX_GROUPS) {
			throw BusinessException.invalid("An event can have up to %d groups.".formatted(MAX_GROUPS));
		}
		var position = jdbc.sql("SELECT coalesce(max(position) + 1, 0) FROM event_groups WHERE event_id = :event")
			.param("event", eventId).query(Integer.class).single();
		insertGroup(eventId, groupName(input.name()), colour(input.colour(), count), position);
		return reader.detail(eventId, userId);
	}

	@Transactional
	public EventViews.Detail updateGroup(UUID eventId, UUID groupId, GroupInput input, UUID userId) {
		access.requireAdmin(eventId, userId);
		var name = groupName(input.name());
		var colour = input.colour() == null ? null : input.colour().strip();
		if (colour != null && !COLOUR.matcher(colour).matches()) {
			throw BusinessException.invalid("Pick a six-digit colour.");
		}
		try {
			var changed = jdbc.sql("""
					UPDATE event_groups SET name = :name, colour = coalesce(:colour, colour) WHERE id = :id AND event_id = :event
					""").param("name", name).param("colour", colour).param("id", groupId).param("event", eventId).update();
			if (changed == 0) {
				throw BusinessException.notFound("That group isn’t in this event any more.");
			}
		}
		catch (DuplicateKeyException taken) {
			throw BusinessException.conflict("There’s already a group called %s.".formatted(name));
		}
		return reader.detail(eventId, userId);
	}

	/** Removes a group. Its people and entries stay, belonging to no group. */
	@Transactional
	public EventViews.Detail removeGroup(UUID eventId, UUID groupId, UUID userId) {
		access.requireAdmin(eventId, userId);
		jdbc.sql("DELETE FROM event_groups WHERE id = :id AND event_id = :event").param("id", groupId).param("event", eventId).update();
		return reader.detail(eventId, userId);
	}

	private void insertGroup(UUID eventId, String name, String colour, int position) {
		try {
			jdbc.sql("""
					INSERT INTO event_groups (id, event_id, name, colour, position, created_at)
					VALUES (:id, :event, :name, :colour, :position, :now)
					""").param("id", UUID.randomUUID()).param("event", eventId).param("name", name).param("colour", colour)
				.param("position", position).param("now", now()).update();
		}
		catch (DuplicateKeyException taken) {
			throw BusinessException.conflict("There’s already a group called %s.".formatted(name));
		}
	}

	/* ------------------------------------------------------------------ */
	/* People                                                               */
	/* ------------------------------------------------------------------ */

	/**
	 * Adds names, one or a pasted list. The same name twice is allowed (two Kofis are normal); the
	 * screen points it out before sending.
	 */
	@Transactional
	public EventViews.Detail addPeople(UUID eventId, List<PersonInput> people, UUID userId) {
		var event = access.requireAdmin(eventId, userId);
		if (people == null || people.isEmpty()) {
			throw BusinessException.invalid("Add at least one name.");
		}
		if (people.size() > 500) {
			throw BusinessException.invalid("Add up to 500 names at a time.");
		}
		var count = jdbc.sql("SELECT count(*) FROM event_people WHERE event_id = :event").param("event", eventId)
			.query(Integer.class).single();
		if (count + people.size() > MAX_PEOPLE) {
			throw BusinessException.invalid("An event can have up to %d people.".formatted(MAX_PEOPLE));
		}
		var groups = groupIds(eventId);
		var now = now();
		for (var person : people) {
			var name = personName(person.name());
			if (person.groupId() != null && !groups.contains(person.groupId())) {
				throw BusinessException.invalid("That group isn’t in this event any more.");
			}
			jdbc.sql("""
					INSERT INTO event_people (id, event_id, display_name, group_id, source, added_by, created_at, updated_at)
					VALUES (:id, :event, :name, :group, 'admin', :user, :now, :now)
					""").param("id", UUID.randomUUID()).param("event", eventId).param("name", name).param("group", person.groupId())
				.param("user", userId).param("now", now).update();
		}
		happened.record("event.people-added", "event", eventId, userId, event.organisationId(), null, Map.of("count", people.size()));
		return reader.detail(eventId, userId);
	}

	/**
	 * Renames someone or moves them to another group. Their entries in single games move with them;
	 * a pair or team keeps the group it was entered for.
	 */
	@Transactional
	public EventViews.Detail updatePerson(UUID eventId, UUID personId, PersonInput input, UUID userId) {
		access.requireAdmin(eventId, userId);
		var name = personName(input.name());
		if (input.groupId() != null && !groupIds(eventId).contains(input.groupId())) {
			throw BusinessException.invalid("That group isn’t in this event any more.");
		}
		var changed = jdbc.sql("""
				UPDATE event_people SET display_name = :name, group_id = :group, updated_at = :now WHERE id = :id AND event_id = :event
				""").param("name", name).param("group", input.groupId()).param("now", now()).param("id", personId)
			.param("event", eventId).update();
		if (changed == 0) {
			throw BusinessException.notFound("That person isn’t in this event any more.");
		}
		moveSingles(personId, name, input.groupId());
		return reader.detail(eventId, userId);
	}

	/** Takes someone out of the event, and out of every game they were in. Not once a draw counts on them. */
	@Transactional
	public EventViews.Detail removePerson(UUID eventId, UUID personId, UUID userId) {
		var event = access.requireAdmin(eventId, userId);
		var drawn = jdbc.sql("""
				SELECT g.name FROM event_entry_people ep JOIN event_games g ON g.id = ep.game_id
				WHERE ep.person_id = :person AND g.status <> 'open' AND g.entry_kind = 'single' LIMIT 1
				""").param("person", personId).query(String.class).optional();
		if (drawn.isPresent()) {
			throw BusinessException.conflict("They’re in the draw for %s, so they can’t be taken out of the event now.".formatted(drawn.get()));
		}
		// Their single entries go with them; the pairs and teams they were in stay, without them.
		jdbc.sql("""
				DELETE FROM event_entries e USING event_entry_people ep, event_games g
				WHERE ep.entry_id = e.id AND g.id = e.game_id AND ep.person_id = :person AND g.entry_kind = 'single'
				""").param("person", personId).update();
		var changed = jdbc.sql("DELETE FROM event_people WHERE id = :id AND event_id = :event").param("id", personId)
			.param("event", eventId).update();
		if (changed == 0) {
			throw BusinessException.notFound("That person isn’t in this event any more.");
		}
		happened.record("event.person-removed", "event", eventId, userId, event.organisationId(), null, Map.of());
		return reader.detail(eventId, userId);
	}

	private void moveSingles(UUID personId, String name, UUID groupId) {
		jdbc.sql("""
				UPDATE event_entries e SET name = :name, group_id = :group FROM event_entry_people ep, event_games g
				WHERE ep.entry_id = e.id AND g.id = e.game_id AND ep.person_id = :person AND g.entry_kind = 'single'
				""").param("name", name).param("group", groupId).param("person", personId).update();
	}

	/* ------------------------------------------------------------------ */
	/* Joining with the link                                                */
	/* ------------------------------------------------------------------ */

	public EventViews.JoinPreview preview(String code, UUID userId) {
		return reader.preview(byCode(code), userId);
	}

	/**
	 * Joins with the link, or changes what they joined: their group, and the games they want. Single
	 * games take them straight in; pair and team games take their interest. Games they leave out are
	 * games they're leaving.
	 */
	@Transactional
	public EventViews.Detail join(String code, UUID groupId, List<UUID> gameIds, UUID userId) {
		var eventId = byCode(code);
		var event = access.event(eventId);
		if (!event.open() || !event.registrationOpen()) {
			throw BusinessException.conflict("%s isn’t taking sign-ups now. Ask the organisers to add you.".formatted(event.name()));
		}
		var groups = groupIds(eventId);
		if (!groups.isEmpty() && groupId == null) {
			throw BusinessException.invalid("Pick your group.");
		}
		if (groupId != null && !groups.contains(groupId)) {
			throw BusinessException.invalid("That group isn’t in this event any more.");
		}
		var personId = access.personOf(eventId, userId);
		var account = users.find(userId).orElseThrow(() -> BusinessException.unauthenticated("Sign in again."));
		var name = personName(account.name() == null || account.name().isBlank() ? "Player" : account.name());
		if (personId == null) {
			personId = UUID.randomUUID();
			try {
				jdbc.sql("""
						INSERT INTO event_people (id, event_id, display_name, user_id, group_id, source, added_by, created_at, updated_at)
						VALUES (:id, :event, :name, :user, :group, 'link', :user, :now, :now)
						""").param("id", personId).param("event", eventId).param("name", name).param("user", userId).param("group", groupId)
					.param("now", now()).update();
			}
			catch (DuplicateKeyException twice) {
				throw BusinessException.conflict("You’ve already joined. Open the event to see your games.");
			}
			happened.record("event.joined", "event", eventId, userId, event.organisationId(), null, Map.of());
		}
		else {
			var current = jdbc.sql("SELECT display_name FROM event_people WHERE id = :id").param("id", personId).query(String.class).single();
			jdbc.sql("UPDATE event_people SET group_id = :group, updated_at = :now WHERE id = :id").param("group", groupId)
				.param("now", now()).param("id", personId).update();
			moveSingles(personId, current, groupId);
		}
		setGames(eventId, personId, gameIds == null ? List.of() : gameIds);
		return reader.detail(eventId, userId);
	}

	/** Enters the signed-in player in one game, from the event's page. */
	@Transactional
	public EventViews.Detail enter(UUID eventId, UUID gameId, UUID userId) {
		var event = access.event(eventId);
		var personId = access.personOf(eventId, userId);
		if (personId == null) {
			throw BusinessException.conflict("Join the event first, with its link.");
		}
		if (!event.open() || !event.registrationOpen()) {
			throw BusinessException.conflict("%s isn’t taking sign-ups now. Ask the organisers to add you.".formatted(event.name()));
		}
		var game = access.game(eventId, gameId);
		var person = entries.people(eventId, List.of(personId)).getFirst();
		if ("single".equals(game.entryKind())) {
			entries.enterSingle(game, person);
		}
		else {
			entries.interest(game, personId);
		}
		return reader.detail(eventId, userId);
	}

	/** Takes the signed-in player out of one game. */
	@Transactional
	public EventViews.Detail leave(UUID eventId, UUID gameId, UUID userId) {
		access.event(eventId);
		var personId = access.personOf(eventId, userId);
		if (personId == null) {
			throw BusinessException.notFound(EventAccess.GONE);
		}
		entries.leave(access.game(eventId, gameId), personId);
		return reader.detail(eventId, userId);
	}

	private void setGames(UUID eventId, UUID personId, List<UUID> wanted) {
		var want = new LinkedHashSet<>(wanted);
		var person = entries.people(eventId, List.of(personId)).getFirst();
		for (var gameId : reader.gamesOf(personId)) {
			if (!want.remove(gameId)) {
				entries.leave(access.game(eventId, gameId), personId);
			}
		}
		for (var gameId : want) {
			var game = access.game(eventId, gameId);
			if ("single".equals(game.entryKind())) {
				entries.enterSingle(game, person);
			}
			else {
				entries.interest(game, personId);
			}
		}
	}

	private UUID byCode(String code) {
		var clean = code == null ? "" : code.strip();
		return jdbc.sql("SELECT id FROM events WHERE join_code = :code").param("code", clean).query(UUID.class).optional()
			.orElseThrow(() -> BusinessException.notFound("That link doesn’t work any more. Ask the organisers for a new one."));
	}

	private List<UUID> groupIds(UUID eventId) {
		return jdbc.sql("SELECT id FROM event_groups WHERE event_id = :event").param("event", eventId).query(UUID.class).list();
	}

	/* ------------------------------------------------------------------ */
	/* Checking what was typed                                              */
	/* ------------------------------------------------------------------ */

	private record Details(String name, LocalDate startsOn, LocalDate endsOn, String timezone, String country, String venueName,
			String venueArea, String mapUrl, boolean registrationOpen, Integer[] points) {
	}

	private Details details(EventInput input, boolean registrationOpen, Integer[] points) {
		var name = GameSettings.text(input.name(), 100);
		if (name == null) {
			throw BusinessException.invalid("Give the event a name.");
		}
		if (input.startsOn() == null) {
			throw BusinessException.invalid("Pick the day it starts.");
		}
		var endsOn = input.endsOn() == null ? input.startsOn() : input.endsOn();
		if (endsOn.isBefore(input.startsOn())) {
			throw BusinessException.invalid("It can’t end before it starts.");
		}
		if (endsOn.isAfter(input.startsOn().plusDays(30))) {
			throw BusinessException.invalid("An event runs for up to a month. Make a second one for the rest.");
		}
		var country = input.country() == null || input.country().isBlank() ? Market.DEFAULT : input.country().strip().toUpperCase(Locale.ROOT);
		if (!Market.exists(country)) {
			throw BusinessException.invalid("Pick a country.");
		}
		String timezone;
		try {
			timezone = input.timezone() == null || input.timezone().isBlank() ? Market.get(country).timezone()
					: ZoneId.of(input.timezone().strip()).getId();
		}
		catch (DateTimeException unknown) {
			throw BusinessException.invalid("Pick a time zone.");
		}
		var venue = input.venue();
		var placing = input.placingPoints() == null || input.placingPoints().isEmpty() ? List.of(points) : input.placingPoints();
		if (placing.size() > 8 || placing.stream().anyMatch(p -> p == null || p < 0 || p > 100) || placing.stream().allMatch(p -> p == 0)) {
			throw BusinessException.invalid("Give each place up to 100 points, for up to 8 places.");
		}
		return new Details(name, input.startsOn(), endsOn, timezone, country, venue == null ? null : GameSettings.text(venue.name(), 120),
				venue == null ? null : GameSettings.text(venue.area(), 120), venue == null ? null : MapLink.normalise(venue.mapUrl()),
				input.registrationOpen() == null ? registrationOpen : input.registrationOpen(), placing.toArray(Integer[]::new));
	}

	private static String groupName(String typed) {
		var name = GameSettings.text(typed, 40);
		if (name == null) {
			throw BusinessException.invalid("Give the group a name.");
		}
		return name;
	}

	private static String colour(String typed, int index) {
		if (typed == null || typed.isBlank()) {
			return PALETTE.get(index % PALETTE.size());
		}
		if (!COLOUR.matcher(typed.strip()).matches()) {
			throw BusinessException.invalid("Pick a six-digit colour.");
		}
		return typed.strip();
	}

	static String personName(String typed) {
		var name = GameSettings.text(typed, 80);
		if (name == null) {
			throw BusinessException.invalid("Give each person a name.");
		}
		return name;
	}

	/** A random code for a link, letters and digits only, so it survives being read out or retyped. */
	private static String code(int bytes) {
		var raw = new byte[bytes];
		RANDOM.nextBytes(raw);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw).replace('-', 'x').replace('_', 'y');
	}

	private OffsetDateTime now() {
		return clock.instant().atOffset(ZoneOffset.UTC);
	}

}
