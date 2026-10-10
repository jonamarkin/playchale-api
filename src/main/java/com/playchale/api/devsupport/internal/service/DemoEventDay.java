package com.playchale.api.devsupport.internal.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.playchale.api.shared.draws.RoundRobin;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The demo games day, as in the web app's seed (webapp/app/services/mock/seed-events.ts): Hillview
 * Chapel, an invented church, runs its fellowships' games day today. Ama owns the workspace, Esi
 * coordinates table tennis and ludo, and Kwame joined with the link. Everyone else is a name an admin
 * typed in, as most people at a games day are. The day runs to a plan: oware was played first thing,
 * ludo's heats are in and its final is next, the football semi-finals are under way on the main
 * pitch, and the Bible quiz final is late morning; everything else is still taking entries.
 *
 * <p>Entries and matches are made in the same order as the web app's, so they get the same IDs.
 */
final class DemoEventDay {

	static final String ORGANISATION = "org-hillview";

	static final String EVENT = "ev-hillview";

	private static final List<String[]> GROUPS = List.of(
			new String[] { "evg-joy", "Joy Fellowship", "#1f6feb" },
			new String[] { "evg-faith", "Faith Fellowship", "#d1342f" },
			new String[] { "evg-hope", "Hope Fellowship", "#2f9e44" },
			new String[] { "evg-grace", "Grace Fellowship", "#e8a400" });

	/** Invented names, eight a fellowship. */
	private static final Map<String, List<String>> NAMES = new LinkedHashMap<>();

	static {
		NAMES.put("evg-joy", List.of("Kofi Boadu", "Efua Mensah", "Yaw Ofori", "Adjoa Lartey", "Kwesi Arthur", "Naa Dedei", "Fiifi Quansah", "Akua Sarpong"));
		NAMES.put("evg-faith", List.of("Kojo Appiah", "Abena Frimpong", "Nana Yeboah", "Esinam Agbeko", "Selorm Dzokoto", "Afia Boakye", "Ekow Hagan", "Dzifa Amegah"));
		NAMES.put("evg-hope", List.of("Kwabena Ntim", "Afua Konadu", "Elikem Tetteh", "Maame Esi", "Kweku Danso", "Adwoa Pokuaa", "Nii Odartey", "Yaa Asantewaa"));
		NAMES.put("evg-grace", List.of("Kwaku Owusu", "Akosua Dapaah", "Mawuli Kpodo", "Araba Eshun", "Kobby Annan", "Esi Amissah", "Paa Kwesi", "Dede Ankrah"));
	}

	private final JdbcClient jdbc;

	private final ZonedDateTime now;

	private int seed;

	DemoEventDay(JdbcClient jdbc, ZonedDateTime now) {
		this.jdbc = jdbc;
		this.now = now;
	}

	void load() {
		var created = utc(daysAgo(30));
		jdbc.sql("""
				INSERT INTO organisations (id, name, slug, country, primary_colour, corporate_enabled, created_by, created_at, updated_at)
				VALUES (:id, 'Hillview Chapel', 'hillview-chapel', 'GH', '#5b2a86', false, :owner, :created, :created)
				""").param("id", id(ORGANISATION)).param("owner", id("u-ama")).param("created", created).update();
		member("u-ama", "owner", daysAgo(30));
		member("u-esi", "official", daysAgo(20));

		var today = now.toLocalDate();
		jdbc.sql("""
				INSERT INTO events (id, organisation_id, name, starts_on, ends_on, timezone, country, venue_name, venue_area, status,
				                    registration_open, join_code, board_token, placing_points, created_by, created_at, updated_at)
				VALUES (:id, :organisation, 'Hillview Games Day', :day, :day, 'Africa/Accra', 'GH', 'Hillview Chapel grounds', 'Adenta',
				        'open', true, 'hillview-demo', 'hillview-board-demo', '{5,3,1}', :owner, :created, :created)
				""").param("id", id(EVENT)).param("organisation", id(ORGANISATION)).param("day", today).param("owner", id("u-ama"))
			.param("created", utc(daysAgo(7))).update();

		for (int i = 0; i < GROUPS.size(); i++) {
			var g = GROUPS.get(i);
			jdbc.sql("""
					INSERT INTO event_groups (id, event_id, name, colour, position, created_at)
					VALUES (:id, :event, :name, :colour, :position, :created)
					""").param("id", id(g[0])).param("event", id(EVENT)).param("name", g[1]).param("colour", g[2]).param("position", i)
				.param("created", utc(daysAgo(7))).update();
		}
		for (var group : NAMES.entrySet()) {
			for (var name : group.getValue()) {
				person(person(name), name, group.getKey(), null, "admin");
			}
		}
		person("evp-kwame", "Kwame Asante", "evg-hope", "u-kwame", "link");

		seed = 0;
		game("evgm-football", "football", "Football", "Men", "team", 7, "knockout", "score", null, false, true, null, null, "Main pitch", 0);
		for (var g : GROUPS) {
			entry("evgm-football", g[1], g[0], everyOther(g[0], 0));
		}
		game("evgm-volleyball", "volleyball", "Volleyball", "Women", "team", null, "league", "sets", 3, false, false, null, null, "Church court", 1);
		for (var g : GROUPS) {
			for (var name : everyOther(g[0], 1).subList(0, 3)) {
				jdbc.sql("INSERT INTO event_game_interest (game_id, person_id, created_at) VALUES (:game, :person, :at)")
					.param("game", id("evgm-volleyball")).param("person", id(person(name))).param("at", utc(daysAgo(3))).update();
			}
		}
		game("evgm-table-tennis", "table-tennis", "Table tennis", "Open", "single", null, "knockout", "sets", 3, false, false, null, null, "Hall", 2);
		singles("evgm-table-tennis", 1, 2);
		entryOf("evgm-table-tennis", "Kwame Asante", "evg-hope", List.of("evp-kwame"));
		game("evgm-oware", "oware", "Oware", null, "single", null, "league", "outcome", null, true, false, null, null, "Hall", 3);
		singles("evgm-oware", 1, 3);
		game("evgm-draughts", "draughts", "Draughts", null, "single", null, "knockout", "outcome", null, false, false, null, null, "Hall", 4);
		singles("evgm-draughts", 1, 4);
		game("evgm-ludo", "ludo", "Ludo", null, "single", null, "placings", "placings", null, false, false, 4, 1, "Sunday school room", 5);
		singles("evgm-ludo", 2, 5);
		game("evgm-sack-race", "custom", "Sack race", "Under 12", "single", null, "placings", "placings", null, false, false, 6, 2, "Main pitch", 6);
		game("evgm-quiz", "quiz", "Bible quiz", null, "team", null, "placings", "placings", null, false, false, 4, 1, "Main auditorium", 7);
		for (var g : GROUPS) {
			entry("evgm-quiz", g[1], g[0], NAMES.get(g[0]).subList(6, 8));
		}
		game("evgm-tug-of-war", "tug-of-war", "Tug of war", null, "team", null, "knockout", "outcome", null, false, false, null, null, "Main pitch", 8);
		for (var g : GROUPS) {
			entry("evgm-tug-of-war", g[1], g[0], List.of());
		}
		playOware();
		runLudoHeats();
		footballDay();
		quizFinal();
		plan("evgm-oware", "08:00", 15, "Hall");
		plan("evgm-ludo", "09:00", 20, "Sunday school room");
		plan("evgm-football", "10:00", 30, "Main pitch");
		plan("evgm-quiz", "11:00", 30, "Main auditorium");
		for (var game : List.of("evgm-table-tennis", "evgm-ludo")) {
			jdbc.sql("INSERT INTO event_game_coordinators (game_id, user_id, added_by, created_at) VALUES (:game, :user, :by, :at)")
				.param("game", id(game)).param("user", id("u-esi")).param("by", id("u-ama")).param("at", utc(daysAgo(5))).update();
		}
	}

	/** Oware, everyone against everyone in entry order, all six played: a league with its places decided. */
	private void playOware() {
		var entries = List.of("eve-10", "eve-11", "eve-12", "eve-13");
		var rounds = RoundRobin.rounds(entries.stream().map(DemoEventDay::id).toList());
		var outcomes = List.of("home", "draw", "away", "home", "home", "draw");
		int played = 0;
		for (int r = 0; r < rounds.size(); r++) {
			for (int s = 0; s < rounds.get(r).size(); s++) {
				var pairing = rounds.get(r).get(s);
				var outcome = outcomes.get(played++);
				var winner = "home".equals(outcome) ? pairing.home() : "away".equals(outcome) ? pairing.away() : null;
				// Planned from 8:00 in the hall, a quarter of an hour each.
				var turn = r * 2 + s;
				jdbc.sql("""
						INSERT INTO event_matches (id, game_id, round, slot, home_entry_id, away_entry_id, winner_entry_id, decided_by,
						                           recorded_by, recorded_at, starts_at, location)
						VALUES (:id, :game, :round, :slot, :home, :away, :winner, 'score', :by, :at, :startsAt, 'Hall')
						""").param("id", id("evm-oware-r%ds%d".formatted(r + 1, s))).param("game", id("evgm-oware")).param("round", r + 1)
					.param("slot", s).param("home", pairing.home()).param("away", pairing.away()).param("winner", winner)
					.param("by", id("u-ama")).param("at", utc(now.minusMinutes(150 - played * 15L).toInstant()))
					.param("startsAt", onTheDay(8 + turn / 4, (turn % 4) * 15)).update();
			}
		}
		jdbc.sql("UPDATE event_games SET status = 'finished' WHERE id = :id").param("id", id("evgm-oware")).update();
	}

	/** Ludo: two heats of four run, the final between their winners still to play. */
	private void runLudoHeats() {
		var entries = java.util.stream.IntStream.rangeClosed(18, 25).mapToObj(i -> id("eve-" + i)).toList();
		// Dealt like cards into two heats of four, as the draw does it (events: Standings.heats).
		var heats = List.of(List.of(entries.get(0), entries.get(2), entries.get(4), entries.get(6)),
				List.of(entries.get(1), entries.get(3), entries.get(5), entries.get(7)));
		var places = List.of(List.of(2, 1, 4, 3), List.of(1, 2, 3, 4));
		var winners = new java.util.ArrayList<UUID>();
		for (int h = 0; h < heats.size(); h++) {
			var heatId = id("evh-ludo-heat-" + (h + 1));
			jdbc.sql("""
					INSERT INTO event_heats (id, game_id, stage, number, recorded_by, recorded_at, starts_at, location)
					VALUES (:id, :game, 'heat', :n, :by, :at, :startsAt, 'Sunday school room')
					""").param("id", heatId).param("game", id("evgm-ludo")).param("n", h + 1).param("by", id("u-esi"))
				.param("at", utc(now.minusMinutes(60 - h * 20L).toInstant())).param("startsAt", onTheDay(9, h * 20)).update();
			for (int lane = 0; lane < heats.get(h).size(); lane++) {
				var place = places.get(h).get(lane);
				jdbc.sql("INSERT INTO event_heat_entries (heat_id, entry_id, lane, place) VALUES (:heat, :entry, :lane, :place)")
					.param("heat", heatId).param("entry", heats.get(h).get(lane)).param("lane", lane + 1).param("place", place).update();
				if (place == 1) {
					winners.add(heats.get(h).get(lane));
				}
			}
		}
		var finalId = id("evh-ludo-final-1");
		jdbc.sql("""
				INSERT INTO event_heats (id, game_id, stage, number, starts_at, location)
				VALUES (:id, :game, 'final', 1, :startsAt, 'Sunday school room')
				""").param("id", finalId).param("game", id("evgm-ludo")).param("startsAt", onTheDay(9, 40)).update();
		for (int lane = 0; lane < winners.size(); lane++) {
			jdbc.sql("INSERT INTO event_heat_entries (heat_id, entry_id, lane) VALUES (:heat, :entry, :lane)").param("heat", finalId)
				.param("entry", winners.get(lane)).param("lane", lane + 1).update();
		}
		jdbc.sql("UPDATE event_games SET status = 'drawn' WHERE id = :id").param("id", id("evgm-ludo")).update();
	}

	/**
	 * Football: the four fellowships' teams drawn in entry order, on the main pitch from 10:00, half
	 * an hour a match. Joy beat Grace in the first semi-final; the second is next, then third place and
	 * the final.
	 */
	private void footballDay() {
		footballMatch("evm-football-r1s0", 1, 0, false, "eve-1", "eve-4", onTheDay(10, 0));
		jdbc.sql("""
				UPDATE event_matches SET home_score = 2, away_score = 1, winner_entry_id = :winner, decided_by = 'score', recorded_by = :by,
				       recorded_at = :at
				WHERE id = :id
				""").param("winner", id("eve-1")).param("by", id("u-ama")).param("at", utc(now.minusMinutes(25).toInstant()))
			.param("id", id("evm-football-r1s0")).update();
		footballMatch("evm-football-r1s1", 1, 1, false, "eve-2", "eve-3", onTheDay(10, 30));
		footballMatch("evm-football-third", 2, 0, true, "eve-4", null, onTheDay(11, 0));
		footballMatch("evm-football-final", 2, 0, false, "eve-1", null, onTheDay(11, 30));
		jdbc.sql("UPDATE event_games SET status = 'drawn' WHERE id = :id").param("id", id("evgm-football")).update();
	}

	private void footballMatch(String mockId, int round, int slot, boolean third, String home, String away, OffsetDateTime at) {
		jdbc.sql("""
				INSERT INTO event_matches (id, game_id, round, slot, third_place, home_entry_id, away_entry_id, starts_at, location)
				VALUES (:id, :game, :round, :slot, :third, :home, :away, :at, 'Main pitch')
				""").param("id", id(mockId)).param("game", id("evgm-football")).param("round", round).param("slot", slot)
			.param("third", third).param("home", id(home)).param("away", away == null ? null : id(away)).param("at", at).update();
	}

	/** The Bible quiz: four teams fit one heat, so the draw makes it the final, at 11:00 in the main auditorium. */
	private void quizFinal() {
		var finalId = id("evh-quiz-final-1");
		jdbc.sql("""
				INSERT INTO event_heats (id, game_id, stage, number, starts_at, location)
				VALUES (:id, :game, 'final', 1, :at, 'Main auditorium')
				""").param("id", finalId).param("game", id("evgm-quiz")).param("at", onTheDay(11, 0)).update();
		for (int lane = 0; lane < 4; lane++) {
			jdbc.sql("INSERT INTO event_heat_entries (heat_id, entry_id, lane) VALUES (:heat, :entry, :lane)").param("heat", finalId)
				.param("entry", id("eve-" + (26 + lane))).param("lane", lane + 1).update();
		}
		jdbc.sql("UPDATE event_games SET status = 'drawn' WHERE id = :id").param("id", id("evgm-quiz")).update();
	}

	/** A game's plan for the day: from {@code clock}, {@code minutes} a match, at {@code location}. */
	private void plan(String game, String clock, int minutes, String location) {
		var parts = clock.split(":");
		jdbc.sql("UPDATE event_games SET starts_at = :at, match_minutes = :minutes, locations = :locations WHERE id = :id")
			.param("at", onTheDay(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]))).param("minutes", minutes)
			.param("locations", new String[] { location }).param("id", id(game)).update();
	}

	/** A time on the day of the games, by Accra's clock (which is UTC). */
	private OffsetDateTime onTheDay(int hour, int minute) {
		return now.toLocalDate().atTime(hour, minute).atOffset(ZoneOffset.UTC);
	}

	private void member(String user, String role, Instant joined) {
		jdbc.sql("""
				INSERT INTO organisation_memberships (organisation_id, user_id, role, created_at, created_by)
				VALUES (:organisation, :user, :role, :joined, :owner)
				""").param("organisation", id(ORGANISATION)).param("user", id(user)).param("role", role).param("joined", utc(joined))
			.param("owner", id("u-ama")).update();
	}

	private void person(String mockId, String name, String group, String user, String source) {
		jdbc.sql("""
				INSERT INTO event_people (id, event_id, display_name, user_id, group_id, source, added_by, created_at, updated_at)
				VALUES (:id, :event, :name, :user, :group, :source, :by, :at, :at)
				""").param("id", id(mockId)).param("event", id(EVENT)).param("name", name).param("user", user == null ? null : id(user))
			.param("group", id(group)).param("source", source).param("by", id(user == null ? "u-ama" : user))
			.param("at", utc(daysAgo("link".equals(source) ? 3 : 6))).update();
	}

	private void game(String mockId, String discipline, String name, String category, String entryKind, Integer teamSize, String format,
			String scoring, Integer bestOf, boolean draws, boolean thirdPlace, Integer heatSize, Integer advance, String location, int position) {
		jdbc.sql("""
				INSERT INTO event_games (id, event_id, discipline, name, category, entry_kind, team_size, format, scoring, best_of,
				                         draws_allowed, third_place, heat_size, advance_per_heat, location, status, position, created_at, updated_at)
				VALUES (:id, :event, :discipline, :name, :category, :kind, :teamSize, :format, :scoring, :bestOf,
				        :draws, :thirdPlace, :heatSize, :advance, :location, 'open', :position, :at, :at)
				""").param("id", id(mockId)).param("event", id(EVENT)).param("discipline", discipline).param("name", name)
			.param("category", category).param("kind", entryKind).param("teamSize", teamSize).param("format", format)
			.param("scoring", scoring).param("bestOf", bestOf).param("draws", draws).param("thirdPlace", thirdPlace)
			.param("heatSize", heatSize).param("advance", advance).param("location", location).param("position", position)
			.param("at", utc(daysAgo(6))).update();
	}

	/** Each group's names from {@code from}, {@code count} of them, each a single entry. */
	private void singles(String game, int count, int from) {
		for (var g : GROUPS) {
			for (var name : NAMES.get(g[0]).subList(from, from + count)) {
				entry(game, name, g[0], List.of(name));
			}
		}
	}

	private void entry(String game, String name, String group, List<String> people) {
		entryOf(game, name, group, people.stream().map(DemoEventDay::person).toList());
	}

	private void entryOf(String game, String name, String group, List<String> personIds) {
		var entryId = "eve-" + (++seed);
		jdbc.sql("""
				INSERT INTO event_entries (id, game_id, name, group_id, seed, status, created_at)
				VALUES (:id, :game, :name, :group, :seed, 'entered', :at)
				""").param("id", id(entryId)).param("game", id(game)).param("name", name).param("group", id(group)).param("seed", seed)
			.param("at", utc(daysAgo(4))).update();
		for (var person : personIds) {
			jdbc.sql("INSERT INTO event_entry_people (entry_id, person_id, game_id) VALUES (:entry, :person, :game)")
				.param("entry", id(entryId)).param("person", id(person)).param("game", id(game)).update();
		}
	}

	/** Every other name in a group, starting at {@code from}: the men's side, or the women's. */
	private static List<String> everyOther(String group, int from) {
		var names = new ArrayList<String>();
		var all = NAMES.get(group);
		for (int i = from; i < all.size(); i += 2) {
			names.add(all.get(i));
		}
		return names;
	}

	/** A name's ID, as the web app's seed makes it: "Kofi Boadu" is evp-kofi-boadu. */
	private static String person(String name) {
		return "evp-" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", "-");
	}

	private Instant daysAgo(int days) {
		return now.minusDays(days).withHour(10).withMinute(0).withSecond(0).withNano(0).toInstant();
	}

	private static UUID id(String mockId) {
		return SeedIds.of(mockId);
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
