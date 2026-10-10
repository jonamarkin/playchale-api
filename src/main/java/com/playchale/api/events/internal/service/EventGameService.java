package com.playchale.api.events.internal.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.playchale.api.events.api.EventActivity;
import com.playchale.api.events.internal.domain.GameSettings;
import com.playchale.api.events.internal.service.EventAccess.GameRow;
import com.playchale.api.events.internal.service.EventEntries.PersonRow;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.shared.events.Happened;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The games in an event: adding them, who coordinates each, and who's entered in them. */
@Service
public class EventGameService {

	private static final int MAX_GAMES = 100;

	private final JdbcClient jdbc;

	private final Clock clock;

	private final EventAccess access;

	private final EventReader reader;

	private final EventEntries entries;

	private final Happened happened;

	private final ApplicationEventPublisher events;

	EventGameService(JdbcClient jdbc, Clock clock, EventAccess access, EventReader reader, EventEntries entries, Happened happened,
			ApplicationEventPublisher events) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.access = access;
		this.reader = reader;
		this.entries = entries;
		this.happened = happened;
		this.events = events;
	}

	/** An entry as a coordinator makes it. A single game's entry is named after its person. */
	public record EntryInput(String name, UUID groupId, List<UUID> personIds) {
	}

	/* ------------------------------------------------------------------ */
	/* Games                                                                */
	/* ------------------------------------------------------------------ */

	@Transactional
	public EventViews.Detail add(UUID eventId, GameSettings.Asked asked, UUID userId) {
		var event = access.requireAdmin(eventId, userId);
		var count = jdbc.sql("SELECT count(*) FROM event_games WHERE event_id = :event").param("event", eventId)
			.query(Integer.class).single();
		if (count >= MAX_GAMES) {
			throw BusinessException.invalid("An event can have up to %d games.".formatted(MAX_GAMES));
		}
		var game = GameSettings.of(asked);
		var id = UUID.randomUUID();
		var position = jdbc.sql("SELECT coalesce(max(position) + 1, 0) FROM event_games WHERE event_id = :event")
			.param("event", eventId).query(Integer.class).single();
		jdbc.sql("""
				INSERT INTO event_games (id, event_id, discipline, name, category, entry_kind, team_size, format, scoring, best_of,
				                         draws_allowed, third_place, heat_size, advance_per_heat, pool_size, advance_per_pool, location,
				                         starts_at, status, position, created_at, updated_at)
				VALUES (:id, :event, :discipline, :name, :category, :entryKind, :teamSize, :format, :scoring, :bestOf,
				        :draws, :thirdPlace, :heatSize, :advance, :poolSize, :advancePerPool, :location, :startsAt, 'open', :position,
				        :now, :now)
				""").param("id", id).param("event", eventId).param("discipline", game.discipline()).param("name", game.name())
			.param("category", game.category()).param("entryKind", game.entryKind()).param("teamSize", game.teamSize())
			.param("format", game.format()).param("scoring", game.scoring()).param("bestOf", game.bestOf())
			.param("draws", game.drawsAllowed()).param("thirdPlace", game.thirdPlace()).param("heatSize", game.heatSize())
			.param("advance", game.advancePerHeat()).param("poolSize", game.poolSize()).param("advancePerPool", game.advancePerPool())
			.param("location", game.location()).param("startsAt", at(game)).param("position", position).param("now", now()).update();
		happened.record("event.game-added", "event", eventId, userId, event.organisationId(), null,
				Map.of("discipline", game.discipline(), "format", game.format()));
		return reader.detail(eventId, userId);
	}

	/**
	 * Changes a game. Its name, category, place and time can always change; how it's played only
	 * until the draw, and who can enter only while nobody has.
	 */
	@Transactional
	public EventViews.Detail update(UUID eventId, UUID gameId, GameSettings.Asked asked, UUID userId) {
		access.requireAdmin(eventId, userId);
		var current = access.game(eventId, gameId);
		var game = GameSettings.of(asked);
		var played = !current.open();
		var settingsChanged = !game.format().equals(current.format()) || !game.scoring().equals(current.scoring())
				|| !game.entryKind().equals(current.entryKind());
		if (played && settingsChanged) {
			throw BusinessException.conflict("The draw for %s is made, so how it’s played can’t change now.".formatted(current.name()));
		}
		if (!game.entryKind().equals(current.entryKind()) && someoneIn(gameId)) {
			throw BusinessException.conflict("People have already entered %s. Take them out before changing who can enter."
				.formatted(current.name()));
		}
		jdbc.sql(played ? """
				UPDATE event_games SET name = :name, category = :category, location = :location, starts_at = :startsAt, updated_at = :now
				WHERE id = :id
				""" : """
				UPDATE event_games SET discipline = :discipline, name = :name, category = :category, entry_kind = :entryKind,
				       team_size = :teamSize, format = :format, scoring = :scoring, best_of = :bestOf, draws_allowed = :draws,
				       third_place = :thirdPlace, heat_size = :heatSize, advance_per_heat = :advance, pool_size = :poolSize,
				       advance_per_pool = :advancePerPool, location = :location, starts_at = :startsAt, updated_at = :now
				WHERE id = :id
				""").param("discipline", game.discipline()).param("name", game.name()).param("category", game.category())
			.param("entryKind", game.entryKind()).param("teamSize", game.teamSize()).param("format", game.format())
			.param("scoring", game.scoring()).param("bestOf", game.bestOf()).param("draws", game.drawsAllowed())
			.param("thirdPlace", game.thirdPlace()).param("heatSize", game.heatSize()).param("advance", game.advancePerHeat())
			.param("poolSize", game.poolSize()).param("advancePerPool", game.advancePerPool())
			.param("location", game.location()).param("startsAt", at(game)).param("now", now()).param("id", gameId).update();
		return reader.detail(eventId, userId);
	}

	/** Takes a game out of the event, with its entries and any results. */
	@Transactional
	public EventViews.Detail remove(UUID eventId, UUID gameId, UUID userId) {
		var event = access.requireAdmin(eventId, userId);
		var game = access.game(eventId, gameId);
		jdbc.sql("DELETE FROM event_games WHERE id = :id").param("id", gameId).update();
		happened.record("event.game-removed", "event", eventId, userId, event.organisationId(), null, Map.of("status", game.status()));
		return reader.detail(eventId, userId);
	}

	/**
	 * Who runs a game. They must hold a seat in the workspace (an official's seat is the usual one),
	 * which is how they got the invite link. Those newly given it are told.
	 */
	@Transactional
	public EventViews.Detail coordinators(UUID eventId, UUID gameId, List<UUID> userIds, UUID userId) {
		var event = access.requireAdmin(eventId, userId);
		var game = access.game(eventId, gameId);
		var wanted = new LinkedHashSet<>(userIds == null ? List.<UUID>of() : userIds);
		if (wanted.size() > 10) {
			throw BusinessException.invalid("A game can have up to 10 coordinators.");
		}
		for (var id : wanted) {
			if (!access.inWorkspace(event, id)) {
				throw BusinessException.invalid("Coordinators need a seat in the workspace. Invite them from Staff first.");
			}
		}
		var before = jdbc.sql("SELECT user_id FROM event_game_coordinators WHERE game_id = :game").param("game", gameId)
			.query(UUID.class).list();
		jdbc.sql("DELETE FROM event_game_coordinators WHERE game_id = :game").param("game", gameId).update();
		for (var id : wanted) {
			jdbc.sql("""
					INSERT INTO event_game_coordinators (game_id, user_id, added_by, created_at) VALUES (:game, :user, :by, :now)
					""").param("game", gameId).param("user", id).param("by", userId).param("now", now()).update();
		}
		var added = wanted.stream().filter(id -> !before.contains(id) && !id.equals(userId)).toList();
		if (!added.isEmpty()) {
			events.publishEvent(new EventActivity.CoordinatorAdded(eventId, event.name(), gameId, game.name(), added, userId));
		}
		return reader.detail(eventId, userId);
	}

	/* ------------------------------------------------------------------ */
	/* Entries                                                              */
	/* ------------------------------------------------------------------ */

	@Transactional
	public EventViews.Detail addEntry(UUID eventId, UUID gameId, EntryInput input, UUID userId) {
		var people = entries.people(eventId, input.personIds() == null ? List.of() : input.personIds());
		// A rep enters their own group's people: the coordinator and organisers anyone.
		var game = access.requireEntrant(eventId, gameId, userId, groupsOf(people, input.groupId(), List.of()));
		var shape = shape(eventId, game, input, people);
		var entryId = entries.create(game, shape.name(), shape.groupId(), people);
		tell(eventId, game, entryId, shape.name(), userId);
		return reader.detail(eventId, userId);
	}

	/** Renames an entry, changes its group, or who's in it. A team's players can change after the draw; a single entry's person can't. */
	@Transactional
	public EventViews.Detail updateEntry(UUID eventId, UUID gameId, UUID entryId, EntryInput input, UUID userId) {
		requireEntry(gameId, entryId);
		var people = entries.people(eventId, input.personIds() == null ? List.of() : input.personIds());
		var game = access.requireEntrant(eventId, gameId, userId, groupsOf(people, input.groupId(), groupsIn(entryId)));
		var shape = shape(eventId, game, input, people);
		var before = jdbc.sql("SELECT person_id FROM event_entry_people WHERE entry_id = :entry").param("entry", entryId)
			.query(UUID.class).list();
		var samePeople = before.size() == people.size() && people.stream().allMatch(p -> before.contains(p.id()));
		if (!samePeople) {
			if ("single".equals(game.entryKind())) {
				EventEntries.requireOpen(game);
			}
			entries.replacePeople(game, entryId, people);
		}
		jdbc.sql("UPDATE event_entries SET name = :name, group_id = :group WHERE id = :id").param("name", shape.name())
			.param("group", shape.groupId()).param("id", entryId).update();
		var newcomers = people.stream().filter(p -> !before.contains(p.id()) && p.userId() != null && !p.userId().equals(userId))
			.map(PersonRow::userId).toList();
		if (!newcomers.isEmpty()) {
			var event = access.event(eventId);
			events.publishEvent(new EventActivity.EntryMade(eventId, event.name(), gameId, game.name(),
					"single".equals(game.entryKind()) ? null : shape.name(), newcomers, userId));
		}
		return reader.detail(eventId, userId);
	}

	/** Takes an entry out, before the draw. Its people go back to being interested, so they aren't lost. */
	@Transactional
	public EventViews.Detail removeEntry(UUID eventId, UUID gameId, UUID entryId, UUID userId) {
		requireEntry(gameId, entryId);
		var game = access.requireEntrant(eventId, gameId, userId, groupsIn(entryId));
		EventEntries.requireOpen(game);
		if (!"single".equals(game.entryKind())) {
			jdbc.sql("""
					INSERT INTO event_game_interest (game_id, person_id, created_at)
					SELECT :game, person_id, :now FROM event_entry_people WHERE entry_id = :entry
					ON CONFLICT DO NOTHING
					""").param("game", gameId).param("now", now()).param("entry", entryId).update();
		}
		jdbc.sql("DELETE FROM event_entries WHERE id = :id").param("id", entryId).update();
		return reader.detail(eventId, userId);
	}

	/**
	 * One team per group, named after it, made of that group's people who asked to play. Groups that
	 * already have a team in this game are left alone, so it can be pressed again as more join.
	 */
	@Transactional
	public EventViews.Detail teamPerGroup(UUID eventId, UUID gameId, UUID userId) {
		var game = access.requireRunner(eventId, gameId, userId);
		if (!"team".equals(game.entryKind())) {
			throw BusinessException.invalid("Only a team game gets a team per group.");
		}
		EventEntries.requireOpen(game);
		var groups = reader.groups(eventId);
		if (groups.isEmpty()) {
			throw BusinessException.invalid("This event has no groups yet. Add them in its settings.");
		}
		var taken = jdbc.sql("SELECT group_id FROM event_entries WHERE game_id = :game AND group_id IS NOT NULL")
			.param("game", gameId).query(UUID.class).list();
		var made = new ArrayList<String>();
		for (var group : groups) {
			if (taken.contains(group.id())) {
				continue;
			}
			var people = entries.people(eventId, jdbc.sql("""
					SELECT p.id FROM event_game_interest i JOIN event_people p ON p.id = i.person_id
					WHERE i.game_id = :game AND p.group_id = :group ORDER BY i.created_at
					""").param("game", gameId).param("group", group.id()).query(UUID.class).list());
			var entryId = entries.create(game, group.name(), group.id(), people);
			tell(eventId, game, entryId, group.name(), userId);
			made.add(group.name());
		}
		if (made.isEmpty()) {
			throw BusinessException.conflict("Every group already has a team in %s.".formatted(game.name()));
		}
		return reader.detail(eventId, userId);
	}

	/* ------------------------------------------------------------------ */

	private record Shape(String name, UUID groupId) {
	}

	/**
	 * What an entry is called and who it's for. One person for a single, two for a pair (named "Ama &
	 * Kojo" unless told otherwise), any number for a team, which needs a name or a group to take it from.
	 */
	private Shape shape(UUID eventId, GameRow game, EntryInput input, List<PersonRow> people) {
		var groupId = input.groupId();
		if (groupId != null && reader.groups(eventId).stream().noneMatch(g -> g.id().equals(groupId))) {
			throw BusinessException.invalid("That group isn’t in this event any more.");
		}
		var name = GameSettings.text(input.name(), 80);
		switch (game.entryKind()) {
			case "single" -> {
				if (people.size() != 1) {
					throw BusinessException.invalid("Pick one person for %s.".formatted(game.name()));
				}
				return new Shape(people.getFirst().name(), people.getFirst().groupId());
			}
			case "pair" -> {
				if (people.size() != 2) {
					throw BusinessException.invalid("Pick two people for a pair.");
				}
				var shared = Objects.equals(people.get(0).groupId(), people.get(1).groupId()) ? people.get(0).groupId() : null;
				return new Shape(name != null ? name : people.get(0).name() + " & " + people.get(1).name(), groupId != null ? groupId : shared);
			}
			default -> {
				if (name == null && groupId != null) {
					name = reader.groups(eventId).stream().filter(g -> g.id().equals(groupId)).findFirst().orElseThrow().name();
				}
				if (name == null) {
					throw BusinessException.invalid("Give the team a name.");
				}
				return new Shape(name, groupId);
			}
		}
	}

	private void tell(UUID eventId, GameRow game, UUID entryId, String entryName, UUID actorId) {
		var recipients = entries.accountsIn(entryId).stream().filter(id -> !id.equals(actorId)).toList();
		if (!recipients.isEmpty()) {
			var event = access.event(eventId);
			// A single entry is just them: there's no pair or team name worth saying.
			events.publishEvent(new EventActivity.EntryMade(eventId, event.name(), game.id(), game.name(),
					"single".equals(game.entryKind()) ? null : entryName, recipients, actorId));
		}
	}

	/** Every group an entry touches: its people's, and the group it's for. */
	private static List<UUID> groupsOf(List<PersonRow> people, UUID groupId, List<UUID> before) {
		var groups = new ArrayList<UUID>(before);
		people.forEach(p -> groups.add(p.groupId()));
		if (groupId != null) {
			groups.add(groupId);
		}
		return groups;
	}

	/** The groups an entry already touches: the one it's for, and its people's. */
	private List<UUID> groupsIn(UUID entryId) {
		var groups = new ArrayList<UUID>();
		jdbc.sql("""
				SELECT e.group_id FROM event_entries e WHERE e.id = :entry AND e.group_id IS NOT NULL
				UNION ALL SELECT p.group_id FROM event_entry_people ep JOIN event_people p ON p.id = ep.person_id WHERE ep.entry_id = :entry
				""").param("entry", entryId).query((rs, n) -> (UUID) rs.getObject(1)).list().forEach(groups::add);
		return groups;
	}

	private void requireEntry(UUID gameId, UUID entryId) {
		if (jdbc.sql("SELECT count(*) FROM event_entries WHERE id = :id AND game_id = :game").param("id", entryId)
			.param("game", gameId).query(Integer.class).single() == 0) {
			throw BusinessException.notFound("That entry isn’t in this game any more.");
		}
	}

	private boolean someoneIn(UUID gameId) {
		return jdbc.sql("""
				SELECT (SELECT count(*) FROM event_entries WHERE game_id = :game) + (SELECT count(*) FROM event_game_interest WHERE game_id = :game)
				""").param("game", gameId).query(Integer.class).single() > 0;
	}

	private static OffsetDateTime at(GameSettings game) {
		return game.startsAt() == null ? null : game.startsAt().atOffset(ZoneOffset.UTC);
	}

	private OffsetDateTime now() {
		return clock.instant().atOffset(ZoneOffset.UTC);
	}

}
