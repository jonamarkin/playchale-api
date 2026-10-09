package com.playchale.api.events.internal.service;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import com.playchale.api.events.internal.service.EventAccess.GameRow;
import com.playchale.api.shared.error.BusinessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Who plays in a game. A single game takes people straight in; a pair or team game takes their
 * interest, and someone running the game puts them into a pair or team.
 *
 * <p>A person is in at most one entry per game (the database holds that too), and an entry can only
 * come or go before the draw: after it, the draw is built around who's in.
 */
@Component
class EventEntries {

	private final JdbcClient jdbc;

	private final Clock clock;

	EventEntries(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** A person, as an entry needs them. */
	record PersonRow(UUID id, String name, UUID groupId, UUID userId) {
	}

	List<PersonRow> people(UUID eventId, List<UUID> personIds) {
		var unique = List.copyOf(new LinkedHashSet<>(personIds));
		if (unique.isEmpty()) {
			return List.of();
		}
		var found = jdbc.sql("SELECT id, display_name, group_id, user_id FROM event_people WHERE event_id = :event AND id IN (:ids)")
			.param("event", eventId).param("ids", unique)
			.query((rs, n) -> new PersonRow((UUID) rs.getObject(1), rs.getString(2), (UUID) rs.getObject(3), (UUID) rs.getObject(4)))
			.list();
		if (found.size() != unique.size()) {
			throw BusinessException.invalid("Someone in that list isn’t in this event any more.");
		}
		// In the order asked for, which is the order a pair's name reads in.
		return unique.stream().map(id -> found.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow()).toList();
	}

	/** Puts one person into a single game. Nothing happens if they're already in. */
	void enterSingle(GameRow game, PersonRow person) {
		requireOpen(game);
		if (entryOf(game.id(), person.id()) != null) {
			return;
		}
		create(game, person.name(), person.groupId(), List.of(person));
	}

	/** Says a person wants to play a pair or team game. Nothing happens if they're already in it. */
	void interest(GameRow game, UUID personId) {
		requireOpen(game);
		if (entryOf(game.id(), personId) != null) {
			return;
		}
		jdbc.sql("""
				INSERT INTO event_game_interest (game_id, person_id, created_at) VALUES (:game, :person, :now)
				ON CONFLICT DO NOTHING
				""").param("game", game.id()).param("person", personId).param("now", clock.instant().atOffset(ZoneOffset.UTC)).update();
	}

	/**
	 * Takes a person out of a game: their single entry goes, they leave their pair or team (which
	 * stays), and their interest goes.
	 */
	void leave(GameRow game, UUID personId) {
		jdbc.sql("DELETE FROM event_game_interest WHERE game_id = :game AND person_id = :person")
			.param("game", game.id()).param("person", personId).update();
		var entry = entryOf(game.id(), personId);
		if (entry == null) {
			return;
		}
		requireOpen(game);
		if ("single".equals(game.entryKind())) {
			jdbc.sql("DELETE FROM event_entries WHERE id = :id").param("id", entry).update();
		}
		else {
			jdbc.sql("DELETE FROM event_entry_people WHERE entry_id = :entry AND person_id = :person")
				.param("entry", entry).param("person", personId).update();
		}
	}

	/** A new entry, in after everyone already in. Their interest in the game is settled by it. */
	UUID create(GameRow game, String name, UUID groupId, List<PersonRow> people) {
		requireOpen(game);
		refuseTaken(game, people, null);
		var id = UUID.randomUUID();
		var seed = jdbc.sql("SELECT coalesce(max(seed), 0) + 1 FROM event_entries WHERE game_id = :game").param("game", game.id())
			.query(Integer.class).single();
		jdbc.sql("""
				INSERT INTO event_entries (id, game_id, name, group_id, seed, status, created_at)
				VALUES (:id, :game, :name, :group, :seed, 'entered', :now)
				""").param("id", id).param("game", game.id()).param("name", name).param("group", groupId).param("seed", seed)
			.param("now", clock.instant().atOffset(ZoneOffset.UTC)).update();
		addPeople(game, id, people);
		return id;
	}

	/** Who's in an entry now. Anyone dropped is out of the game; anyone added stops being only interested. */
	void replacePeople(GameRow game, UUID entryId, List<PersonRow> people) {
		refuseTaken(game, people, entryId);
		jdbc.sql("DELETE FROM event_entry_people WHERE entry_id = :entry").param("entry", entryId).update();
		addPeople(game, entryId, people);
	}

	private void addPeople(GameRow game, UUID entryId, List<PersonRow> people) {
		for (var person : people) {
			jdbc.sql("INSERT INTO event_entry_people (entry_id, person_id, game_id) VALUES (:entry, :person, :game)")
				.param("entry", entryId).param("person", person.id()).param("game", game.id()).update();
			jdbc.sql("DELETE FROM event_game_interest WHERE game_id = :game AND person_id = :person")
				.param("game", game.id()).param("person", person.id()).update();
		}
	}

	/** Refuses anyone already in another entry of this game, by name, so the coordinator knows who. */
	private void refuseTaken(GameRow game, List<PersonRow> people, UUID except) {
		var taken = new ArrayList<String>();
		for (var person : people) {
			var entry = entryOf(game.id(), person.id());
			if (entry != null && !entry.equals(except)) {
				taken.add(person.name());
			}
		}
		if (!taken.isEmpty()) {
			throw BusinessException.conflict("%s already %s in %s.".formatted(String.join(" and ", taken),
					taken.size() == 1 ? "is" : "are", game.name()));
		}
	}

	UUID entryOf(UUID gameId, UUID personId) {
		return jdbc.sql("SELECT entry_id FROM event_entry_people WHERE game_id = :game AND person_id = :person")
			.param("game", gameId).param("person", personId).query(UUID.class).optional().orElse(null);
	}

	/** The accounts in an entry, to tell them about it. */
	List<UUID> accountsIn(UUID entryId) {
		return jdbc.sql("""
				SELECT p.user_id FROM event_entry_people ep JOIN event_people p ON p.id = ep.person_id
				WHERE ep.entry_id = :entry AND p.user_id IS NOT NULL
				""").param("entry", entryId).query(UUID.class).list();
	}

	static void requireOpen(GameRow game) {
		if (!game.open()) {
			throw BusinessException.conflict("The draw for %s is made, so who’s in can’t change now.".formatted(game.name()));
		}
	}

}
