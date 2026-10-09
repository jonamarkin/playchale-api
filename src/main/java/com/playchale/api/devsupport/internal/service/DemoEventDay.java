package com.playchale.api.devsupport.internal.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The demo games day, as in the web app's seed (webapp/app/services/mock/seed-events.ts): Hillview
 * Chapel, an invented church, runs its fellowships' games day this Saturday. Ama owns the workspace,
 * Esi coordinates table tennis and ludo, and Kwame joined with the link. Everyone else is a name an
 * admin typed in, as most people at a games day are.
 *
 * <p>Entries are made in the same order as the web app's, so they get the same IDs.
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

		var saturday = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
		jdbc.sql("""
				INSERT INTO events (id, organisation_id, name, starts_on, ends_on, timezone, country, venue_name, venue_area, status,
				                    registration_open, join_code, board_token, placing_points, created_by, created_at, updated_at)
				VALUES (:id, :organisation, 'Hillview Games Day', :day, :day, 'Africa/Accra', 'GH', 'Hillview Chapel grounds', 'Adenta',
				        'open', true, 'hillview-demo', 'hillview-board-demo', '{5,3,1}', :owner, :created, :created)
				""").param("id", id(EVENT)).param("organisation", id(ORGANISATION)).param("day", saturday).param("owner", id("u-ama"))
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
		for (var game : List.of("evgm-table-tennis", "evgm-ludo")) {
			jdbc.sql("INSERT INTO event_game_coordinators (game_id, user_id, added_by, created_at) VALUES (:game, :user, :by, :at)")
				.param("game", id(game)).param("user", id("u-esi")).param("by", id("u-ama")).param("at", utc(daysAgo(5))).update();
		}
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
