package com.playchale.api.events.internal.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
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
import com.playchale.api.shared.maps.MapPin;
import com.playchale.api.shared.maps.Pin;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.events.api.EventActivity;
import org.springframework.context.ApplicationEventPublisher;
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

	private final ApplicationEventPublisher events;

	EventService(JdbcClient jdbc, Clock clock, EventAccess access, EventReader reader, EventEntries entries,
			OrganisationAccess organisations, UserDirectory users, Happened happened, ApplicationEventPublisher events) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.access = access;
		this.reader = reader;
		this.entries = entries;
		this.organisations = organisations;
		this.users = users;
		this.happened = happened;
		this.events = events;
	}

	/** Where it is. A {@code pin} gives the link for directions in place of {@code mapUrl}. */
	public record VenueInput(String name, String area, String mapUrl, MapPin pin) {
	}

	public record GroupInput(String name, String colour) {
	}

	/** An event's details. Groups are only read when it's created; after that they have their own calls. */
	/**
	 * An event's details. {@code entryFee}: what each person pays the organisers, in the event's money,
	 * minor units; left out it stays as it was, and 0 takes it off.
	 */
	public record EventInput(String name, LocalDate startsOn, LocalDate endsOn, String timezone, String country, VenueInput venue,
			List<GroupInput> groups, List<Integer> placingPoints, Boolean registrationOpen, Long entryFee) {
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

	public List<EventViews.Result> resultsOf(UUID userId) {
		return reader.resultsOf(userId);
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
		var details = details(input, true, new Integer[] { 5, 3, 1 }, null);
		var groups = input.groups() == null ? List.<GroupInput>of() : input.groups();
		if (groups.size() > MAX_GROUPS) {
			throw BusinessException.invalid("An event can have up to %d groups.".formatted(MAX_GROUPS));
		}
		var id = UUID.randomUUID();
		var now = now();
		jdbc.sql("""
				INSERT INTO events (id, organisation_id, name, starts_on, ends_on, timezone, country, venue_name, venue_area, map_url,
				                    latitude, longitude, place_id, pin_source, pinned_at, entry_fee,
				                    status, registration_open, join_code, board_token, placing_points, created_by, created_at, updated_at)
				VALUES (:id, :organisation, :name, :startsOn, :endsOn, :timezone, :country, :venueName, :venueArea, :mapUrl,
				        :lat, :lng, :placeId, :pinSource, :pinnedAt, :fee,
				        'open', :registration, :code, :board, :points, :user, :now, :now)
				""").params(pinParams(details.pin())).param("id", id).param("organisation", organisationId).param("name", details.name())
			.param("startsOn", details.startsOn()).param("endsOn", details.endsOn()).param("timezone", details.timezone())
			.param("country", details.country()).param("venueName", details.venueName()).param("venueArea", details.venueArea())
			.param("mapUrl", details.mapUrl()).param("registration", details.registrationOpen()).param("code", code(9))
			.param("board", code(18)).param("points", details.points()).param("fee", fee(input.entryFee(), null)).param("user", userId)
			.param("now", now).update();
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

	/** Running an event again: its new name and days, and whether the people's names come too. */
	public record CopyInput(String name, LocalDate startsOn, LocalDate endsOn, Boolean people) {
	}

	/**
	 * A new event made from this one, for next time: the same place, groups, games (how each is
	 * played, its plan for the day moved to the new days, and its coordinators if they still have a
	 * seat in the workspace), points and entry fee. Never the entries, results, announcements or
	 * links. With {@code people}, the names and their groups come too, as names only: nobody's
	 * account is put in an event they didn't join.
	 */
	@Transactional
	public EventViews.Detail copy(UUID eventId, CopyInput input, UUID userId) {
		var source = access.requireAdmin(eventId, userId);
		var name = GameSettings.text(input == null ? null : input.name(), 100);
		if (name == null) {
			throw BusinessException.invalid("Give the event a name.");
		}
		if (input.startsOn() == null) {
			throw BusinessException.invalid("Pick the day it starts.");
		}
		var was = jdbc.sql("SELECT starts_on, ends_on FROM events WHERE id = :id").param("id", eventId)
			.query((rs, n) -> new LocalDate[] { rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class) }).single();
		var shift = java.time.temporal.ChronoUnit.DAYS.between(was[0], input.startsOn());
		var endsOn = input.endsOn() != null ? input.endsOn() : was[1].plusDays(shift);
		if (endsOn.isBefore(input.startsOn())) {
			throw BusinessException.invalid("It can’t end before it starts.");
		}
		if (endsOn.isAfter(input.startsOn().plusDays(30))) {
			throw BusinessException.invalid("An event runs for up to a month. Make a second one for the rest.");
		}
		var id = UUID.randomUUID();
		var now = now();
		jdbc.sql("""
				INSERT INTO events (id, organisation_id, name, starts_on, ends_on, timezone, country, venue_name, venue_area, map_url,
				                    latitude, longitude, place_id, pin_source, pinned_at, entry_fee,
				                    status, registration_open, join_code, board_token, placing_points, created_by, created_at, updated_at)
				SELECT :id, organisation_id, :name, :startsOn, :endsOn, timezone, country, venue_name, venue_area, map_url,
				       latitude, longitude, place_id, pin_source, pinned_at, entry_fee,
				       'open', true, :code, :board, placing_points, :user, :now, :now
				FROM events WHERE id = :source
				""").param("id", id).param("name", name).param("startsOn", input.startsOn()).param("endsOn", endsOn).param("code", code(9))
			.param("board", code(18)).param("user", userId).param("now", now).param("source", eventId).update();

		var groups = new HashMap<UUID, UUID>();
		jdbc.sql("SELECT id FROM event_groups WHERE event_id = :event").param("event", eventId).query(UUID.class).list()
			.forEach(g -> groups.put(g, UUID.randomUUID()));
		groups.forEach((old, copy) -> jdbc.sql("""
				INSERT INTO event_groups (id, event_id, name, colour, position, created_at)
				SELECT :copy, :event, name, colour, position, :now FROM event_groups WHERE id = :old
				""").param("copy", copy).param("event", id).param("now", now).param("old", old).update());

		var games = jdbc.sql("SELECT id FROM event_games WHERE event_id = :event ORDER BY position").param("event", eventId)
			.query(UUID.class).list();
		for (var game : games) {
			var copy = UUID.randomUUID();
			// The plan's start moves with the event's days; a game's own start time does too.
			jdbc.sql("""
					INSERT INTO event_games (id, event_id, discipline, name, category, entry_kind, team_size, format, scoring, best_of,
					                         draws_allowed, third_place, heat_size, advance_per_heat, pool_size, advance_per_pool,
					                         location, starts_at, match_minutes, locations, status, position, created_at, updated_at)
					SELECT :copy, :event, discipline, name, category, entry_kind, team_size, format, scoring, best_of,
					       draws_allowed, third_place, heat_size, advance_per_heat, pool_size, advance_per_pool,
					       location, starts_at + make_interval(days => :shift), match_minutes, locations, 'open', position, :now, :now
					FROM event_games WHERE id = :old
					""").param("copy", copy).param("event", id).param("shift", (int) shift).param("now", now).param("old", game).update();
			var coordinators = jdbc.sql("SELECT user_id FROM event_game_coordinators WHERE game_id = :game").param("game", game)
				.query(UUID.class).list();
			for (var coordinator : coordinators) {
				if (organisations.roleOf(source.organisationId(), coordinator).isPresent()) {
					jdbc.sql("INSERT INTO event_game_coordinators (game_id, user_id, added_by, created_at) VALUES (:game, :user, :by, :now)")
						.param("game", copy).param("user", coordinator).param("by", userId).param("now", now).update();
				}
			}
		}

		var people = 0;
		if (Boolean.TRUE.equals(input.people())) {
			var rows = jdbc.sql("SELECT display_name, group_id FROM event_people WHERE event_id = :event ORDER BY created_at")
				.param("event", eventId).query((rs, n) -> new Object[] { rs.getString(1), rs.getObject(2) }).list();
			for (var row : rows) {
				jdbc.sql("""
						INSERT INTO event_people (id, event_id, display_name, group_id, source, added_by, created_at, updated_at)
						VALUES (:id, :event, :name, :group, 'admin', :by, :now, :now)
						""").param("id", UUID.randomUUID()).param("event", id).param("name", row[0])
					.param("group", row[1] == null ? null : groups.get((UUID) row[1])).param("by", userId).param("now", now).update();
			}
			people = rows.size();
		}
		happened.record("event.copied", "event", id, userId, source.organisationId(), null,
				Map.of("from", eventId.toString(), "games", games.size(), "people", people));
		return reader.detail(id, userId);
	}

	@Transactional
	public EventViews.Detail update(UUID eventId, EventInput input, UUID userId) {
		var event = access.requireAdmin(eventId, userId);
		// Whatever wasn't sent stays as it was.
		var points = jdbc.sql("SELECT placing_points FROM events WHERE id = :id").param("id", eventId)
			.query((rs, n) -> (Integer[]) rs.getArray(1).getArray()).single();
		var fee = entryFee(eventId);
		var details = details(input, event.registrationOpen(), points, reader.pin(eventId));
		jdbc.sql("""
				UPDATE events SET name = :name, starts_on = :startsOn, ends_on = :endsOn, timezone = :timezone, country = :country,
				       venue_name = :venueName, venue_area = :venueArea, map_url = :mapUrl, registration_open = :registration,
				       latitude = :lat, longitude = :lng, place_id = :placeId, pin_source = :pinSource, pinned_at = :pinnedAt,
				       placing_points = :points, entry_fee = :fee, updated_at = :now
				WHERE id = :id
				""").params(pinParams(details.pin())).param("fee", fee(input.entryFee(), fee)).param("name", details.name()).param("startsOn", details.startsOn()).param("endsOn", details.endsOn())
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
	/* Announcements and the entry fee                                      */
	/* ------------------------------------------------------------------ */

	/** Admins tell everyone something: it's on the event's page and board, and everyone in it with an account hears. */
	@Transactional
	public EventViews.Detail announce(UUID eventId, String body, UUID userId) {
		var event = access.requireAdmin(eventId, userId);
		var text = body == null ? "" : body.strip();
		if (text.isEmpty()) {
			throw BusinessException.invalid("Write what you want everyone to know.");
		}
		if (text.length() > 500) {
			throw BusinessException.invalid("Keep it under 500 characters.");
		}
		jdbc.sql("INSERT INTO event_announcements (id, event_id, body, posted_by, posted_at) VALUES (:id, :event, :body, :user, :now)")
			.param("id", UUID.randomUUID()).param("event", eventId).param("body", text).param("user", userId).param("now", now()).update();
		var recipients = jdbc.sql("""
				SELECT user_id FROM event_people WHERE event_id = :event AND user_id IS NOT NULL AND user_id <> :me
				UNION SELECT c.user_id FROM event_game_coordinators c JOIN event_games g ON g.id = c.game_id
				WHERE g.event_id = :event AND c.user_id <> :me
				""").param("event", eventId).param("me", userId).query(UUID.class).list();
		if (!recipients.isEmpty()) {
			events.publishEvent(new EventActivity.Announced(eventId, event.name(), text, recipients, userId));
		}
		happened.record("event.announced", "event", eventId, userId, event.organisationId(), null, Map.of("people", recipients.size()));
		return reader.detail(eventId, userId);
	}

	@Transactional
	public EventViews.Detail removeAnnouncement(UUID eventId, UUID announcementId, UUID userId) {
		var event = access.requireAdmin(eventId, userId);
		var removed = jdbc.sql("DELETE FROM event_announcements WHERE id = :id AND event_id = :event").param("id", announcementId)
			.param("event", eventId).update();
		if (removed == 0) {
			throw BusinessException.notFound("That announcement has already gone.");
		}
		happened.record("event.announcement-removed", "event", eventId, userId, event.organisationId(), null, Map.of());
		return reader.detail(eventId, userId);
	}

	/**
	 * Admins mark someone's entry fee paid (cash or MoMo, to the organisers: PlayChale never holds the
	 * money), or take that back with null.
	 */
	@Transactional
	public EventViews.Detail markFee(UUID eventId, UUID personId, String via, UUID userId) {
		var event = access.requireAdmin(eventId, userId);
		if (via != null && !"cash".equals(via) && !"momo".equals(via)) {
			throw BusinessException.invalid("Say how they paid: cash or MoMo.");
		}
		var fee = entryFee(eventId);
		if (fee == null && via != null) {
			throw BusinessException.conflict("This event has no entry fee. Set one in Settings first.");
		}
		var marked = jdbc.sql("""
				UPDATE event_people SET fee_paid_via = :via, fee_paid_at = :at, fee_marked_by = :by, updated_at = :now
				WHERE id = :id AND event_id = :event
				""").param("via", via).param("at", via == null ? null : now()).param("by", via == null ? null : userId).param("now", now())
			.param("id", personId).param("event", eventId).update();
		if (marked == 0) {
			throw BusinessException.notFound("That person isn’t in this event any more.");
		}
		happened.record(via == null ? "event.fee-unmarked" : "event.fee-paid", "event", eventId, userId, event.organisationId(), null,
				via == null ? Map.of() : Map.of("via", via));
		return reader.detail(eventId, userId);
	}

	/* ------------------------------------------------------------------ */
	/* Claim links: someone added by name attaches their account            */
	/* ------------------------------------------------------------------ */

	/**
	 * A link for someone an admin added by name, to attach their account. A new one replaces the last,
	 * so a link sent to the wrong person can be undone by sending another.
	 */
	@Transactional
	public EventViews.ClaimLink claimLink(UUID eventId, UUID personId, UUID userId) {
		var event = access.requireAdmin(eventId, userId);
		var person = jdbc.sql("SELECT display_name, user_id FROM event_people WHERE id = :id AND event_id = :event")
			.param("id", personId).param("event", eventId)
			.query((rs, n) -> new Object[] { rs.getString(1), rs.getObject(2) }).optional()
			.orElseThrow(() -> BusinessException.notFound("That person isn’t in this event any more."));
		if (person[1] != null) {
			throw BusinessException.conflict("%s is already in with their own account.".formatted(person[0]));
		}
		var token = code(24);
		jdbc.sql("UPDATE event_people SET claim_hash = :hash, claim_issued_at = :now WHERE id = :id")
			.param("hash", sha256(token)).param("now", now()).param("id", personId).update();
		happened.record("event.claim-link", "event", eventId, userId, event.organisationId(), null, Map.of());
		return new EventViews.ClaimLink("/events/claim?token=" + token);
	}

	/** What a claim link is for, before signing in to it. */
	@Transactional(readOnly = true)
	public EventViews.ClaimPreview claimPreview(String token, UUID viewerId) {
		var row = claimed(token);
		var event = access.event(row.eventId());
		var info = reader.info(row.eventId());
		var group = row.groupId() == null ? null : reader.groups(row.eventId()).stream().filter(g -> g.id().equals(row.groupId())).findFirst().orElse(null);
		// Games they're entered in, and pair or team games they've asked to play.
		var games = jdbc.sql("""
				SELECT count(*) FROM (SELECT game_id FROM event_entry_people WHERE person_id = :person
				                      UNION SELECT game_id FROM event_game_interest WHERE person_id = :person) g
				""").param("person", row.personId()).query(Integer.class).single();
		var mine = viewerId == null ? null : (Boolean) viewerId.equals(row.userId());
		return new EventViews.ClaimPreview(row.eventId(), info.name(), reader.brand(event), info.startsOn(), info.endsOn(), row.name(), group,
				games, row.userId() != null, mine);
	}

	/**
	 * The signed-in player is the person the link was sent to: the name becomes theirs, with every
	 * game it's in. Not if they're already in the event under another name: two of them would count twice.
	 */
	@Transactional
	public EventViews.Detail claim(String token, UUID userId) {
		var row = claimed(token);
		if (userId.equals(row.userId())) {
			return reader.detail(row.eventId(), userId);
		}
		if (row.userId() != null) {
			throw BusinessException.conflict("Someone has already used this link. Ask the organisers for a new one if that wasn’t you.");
		}
		var event = access.event(row.eventId());
		var already = access.personOf(row.eventId(), userId);
		if (already != null) {
			var name = jdbc.sql("SELECT display_name FROM event_people WHERE id = :id").param("id", already).query(String.class).single();
			throw BusinessException.conflict(
					"You’re already in %s as %s. Ask the organisers to remove one of the two first.".formatted(event.name(), name));
		}
		jdbc.sql("UPDATE event_people SET user_id = :user, updated_at = :now WHERE id = :id")
			.param("user", userId).param("now", now()).param("id", row.personId()).update();
		happened.record("event.claimed", "event", row.eventId(), userId, event.organisationId(), null, Map.of());
		return reader.detail(row.eventId(), userId);
	}

	private record ClaimRow(UUID personId, UUID eventId, String name, UUID groupId, UUID userId) {
	}

	/** The person a claim link is for, used or not. */
	private ClaimRow claimed(String token) {
		var hash = token == null || token.isBlank() ? "" : sha256(token.strip());
		return jdbc.sql("SELECT id, event_id, display_name, group_id, user_id FROM event_people WHERE claim_hash = :hash")
			.param("hash", hash)
			.query((rs, n) -> new ClaimRow((UUID) rs.getObject(1), (UUID) rs.getObject(2), rs.getString(3), (UUID) rs.getObject(4),
					(UUID) rs.getObject(5)))
			.optional()
			.orElseThrow(() -> BusinessException.notFound("That link doesn’t work any more. Ask the organisers for a new one."));
	}

	private static String sha256(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
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
			String venueArea, String mapUrl, Pin pin, boolean registrationOpen, Integer[] points) {
	}

	/** The event's entry fee, or null for none. */
	private Long entryFee(UUID eventId) {
		return jdbc.sql("SELECT entry_fee FROM events WHERE id = :id").param("id", eventId).query((rs, n) -> rs.getObject(1, Long.class)).list()
			.stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
	}

	/** An entry fee as sent: left out, {@code current}; 0, none. Up to a hundred thousand in the event's money. */
	private static Long fee(Long sent, Long current) {
		if (sent == null) {
			return current;
		}
		if (sent < 0 || sent > 10_000_000) {
			throw BusinessException.invalid("Set an entry fee of up to 100,000, or none.");
		}
		return sent == 0 ? null : sent;
	}

	/** The pin's columns, all null for none. */
	private static Map<String, Object> pinParams(Pin pin) {
		var params = new HashMap<String, Object>();
		params.put("lat", pin == null ? null : pin.latitude());
		params.put("lng", pin == null ? null : pin.longitude());
		params.put("placeId", pin == null ? null : pin.placeId());
		params.put("pinSource", pin == null ? null : pin.source());
		params.put("pinnedAt", pin == null || pin.pinnedAt() == null ? null : pin.pinnedAt().atOffset(ZoneOffset.UTC));
		return params;
	}

	/** @param pin where it's on the map now, so the same pin sent back keeps its age */
	private Details details(EventInput input, boolean registrationOpen, Integer[] points, Pin pin) {
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
		var venueName = venue == null ? null : GameSettings.text(venue.name(), 120);
		var venueArea = venue == null ? null : GameSettings.text(venue.area(), 120);
		var kept = venue == null ? null : Pin.from(venue.pin(), pin, clock.instant());
		if (kept != null && venueName == null) {
			throw BusinessException.invalid("Name the place too, so people know where they’re going.");
		}
		String mapUrl = null;
		if (venue != null) {
			mapUrl = kept != null ? kept.directions(venueArea == null ? venueName : venueName + ", " + venueArea) : MapLink.normalise(venue.mapUrl());
		}
		return new Details(name, input.startsOn(), endsOn, timezone, country, venueName, venueArea, mapUrl, kept,
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
