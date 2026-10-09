package com.playchale.api.devsupport.internal.service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.playchale.api.market.Market;
import com.playchale.api.users.api.Terms;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The demo data the web app's mock starts with (webapp/app/services/mock/seed.ts), for a laptop and
 * the end-to-end tests: players, venues, games past and upcoming, a league in progress and a few
 * notifications. Times are relative to now, in the market's local time.
 *
 * <p>This is the one place that writes straight into every module's tables, with plain SQL. It has
 * to: demo data needs games already played, results already recorded and fixed IDs, which no module
 * would (or should) let anyone create. It only exists with test support on (the dev profile).
 *
 * <p>Left out on purpose: the mock's "carried over" stats (baseStats), which give some players a
 * record with no games behind it. Here a record only ever comes from results.
 */
@Component
@ConditionalOnBooleanProperty("playchale.test-support")
class DemoSeed {

	private static final String CURRENCY = "GHS";

	private final JdbcClient jdbc;

	private final Clock clock;

	private ZonedDateTime now;

	DemoSeed(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** Accounts worth trying, offered on the sign-in page: mock ID and why it's interesting. */
	record DemoAccount(String mockId, String note) {
	}

	static final List<DemoAccount> DEMO_ACCOUNTS = List.of(
			new DemoAccount("u-kojo", "Runs the Inter-Company League · workspace owner"),
			new DemoAccount("u-ama", "Runs Hillview Chapel\u2019s games day"),
			new DemoAccount("u-kwame", "Apex Ltd\u2019s manager · a roster to submit"),
			new DemoAccount("u-yaw", "Match official · fixtures to referee"),
			new DemoAccount("u-abena", "Three sports · owes a share"),
			new DemoAccount("u-adwoa", "Venue owner · Halfway Line Turf"),
			new DemoAccount("u-sam", "Venue owner · Rebound Courts"));

	void load() {
		now = clock.instant().atZone(Market.get(Market.DEFAULT).zone());
		users();
		venues();
		games();
		series();
		league();
		corporateLeague();
		new DemoEventDay(jdbc, now).load();
		notifications();
		mapPins();
		staff();
		// A contribution to play rather than a cost to split, as in the web app's seed: GH₵ 20 each (280 across 14 spots).
		jdbc.sql("UPDATE games SET pricing = 'per-player' WHERE id = :id").param("id", id("g-labone-tonight")).update();
	}

	/* ------------------------------------------------------------------ repeating games */

	/**
	 * Kojo's Saturday 5-a-side repeats every week, as in the web app's seed: the next one opens when
	 * this one ends, and invites this one's players.
	 */
	private void series() {
		jdbc.sql("""
				INSERT INTO game_series (id, host_id, sport, format, title, duration_minutes, venue_kind, venue_id, pitch_id, venue_name,
				  venue_area, map_url, capacity, total_cost, pricing, visibility, notes, country, timezone, frequency, weekday, kick_off,
				  next_starts_at, opens_at, last_game_id, status, created_at, updated_at)
				SELECT :series, host_id, sport, format, title, duration_minutes, venue_kind, venue_id, pitch_id, venue_name,
				  venue_area, map_url, capacity, total_cost, pricing, visibility, notes, country, timezone, 'weekly',
				  extract(isodow FROM starts_at AT TIME ZONE timezone)::int, (starts_at AT TIME ZONE timezone)::time,
				  starts_at + interval '7 days', starts_at + duration_minutes * interval '1 minute', id, 'active', created_at, created_at
				FROM games WHERE id = :game
				""").param("series", id("s-osu-sat")).param("game", id("g-osu-sat")).update();
		jdbc.sql("UPDATE games SET series_id = :series WHERE id = :game").param("series", id("s-osu-sat")).param("game", id("g-osu-sat"))
			.update();
	}

	/* ------------------------------------------------------------------ staff */

	/**
	 * On a laptop Kojo also works at PlayChale, so the admin desk (the separate admin app) can be
	 * tried: sign in there with his phone, 024 000 0002, code 123456 (not kojo@example.com — that is
	 * only his contact address, and signing in with it makes a new account). Nothing in the player
	 * app reads this, so his demo there is unchanged. In production the first staff row is an INSERT
	 * run by hand on the server; no seed and no endpoint creates one.
	 */
	private void staff() {
		jdbc.sql("INSERT INTO platform_staff (user_id, role, note) VALUES (:id, 'owner', 'demo data')").param("id", id("u-kojo")).update();
	}

	/* ------------------------------------------------------------------ map pins */

	/**
	 * Directions for some places, as in the web app's seed: two partner venues and a beach a host
	 * typed. The rest have none, so "Search in Google Maps" shows too. Approximate demo spots.
	 */
	private static final Map<String, String> VENUE_PINS = Map.of(
			"v-osu", "https://www.google.com/maps/search/?api=1&query=5.5602,-0.1818",
			"v-legon", "https://www.google.com/maps/search/?api=1&query=5.6358,-0.1601");

	private static final Map<String, String> GAME_PINS = Map.of(
			"g-volley-next", "https://www.google.com/maps/search/?api=1&query=5.5606,-0.1497",
			"g-labadi-last", "https://www.google.com/maps/search/?api=1&query=5.5606,-0.1497");

	private void mapPins() {
		VENUE_PINS.forEach((venue, url) -> jdbc.sql("UPDATE venues SET map_url = :url WHERE id = :id").param("url", url).param("id", id(venue)).update());
		GAME_PINS.forEach((game, url) -> jdbc.sql("UPDATE games SET map_url = :url WHERE id = :id").param("url", url).param("id", id(game)).update());
	}

	/* ------------------------------------------------------------------ times and ids */

	/** {@code days} from today at hh:mm local time. */
	private Instant at(int days, int hours, int minutes) {
		return now.toLocalDate().plusDays(days).atTime(hours, minutes).atZone(now.getZone()).toInstant();
	}

	private Instant at(int days, int hours) {
		return at(days, hours, 0);
	}

	/**
	 * A few days ago, but never before the start of this week or this month. A record and a crew
	 * table both open on "this month", so a game seeded a week back leaves them empty on the first of
	 * the month — the demo looks like nobody has ever played. Mirrors the web app's seed
	 * (webapp/app/services/mock/seed.ts).
	 *
	 * @param daysAgo how far back to aim, as a positive number of days
	 */
	private Instant recently(int daysAgo, int hours) {
		var today = now.toLocalDate();
		var intoWeek = today.getDayOfWeek().getValue() - 1;
		var intoMonth = today.getDayOfMonth() - 1;
		var back = Math.min(daysAgo, Math.min(intoWeek, intoMonth));
		var when = today.minusDays(back).atTime(hours, 0).atZone(now.getZone()).toInstant();
		// Pulled onto today, it can land in the future; an hour ago is still "played".
		return when.isAfter(now.toInstant()) ? at(0, Math.max(0, now.getHour() - 1)) : when;
	}

	/**
	 * Days to the next Saturday whose games are still to come: 0 if it's Saturday before 08:00 (the
	 * earliest Saturday game is at 09:00), otherwise the one after. Mirrors the web app's seed.
	 */
	private int daysToSaturday() {
		int jsDay = now.getDayOfWeek().getValue() % 7; // Sunday 0 ... Saturday 6, like JavaScript
		if (jsDay == 6) {
			return now.getHour() < 8 ? 0 : 7;
		}
		return 6 - jsDay;
	}

	private static UUID id(String mockId) {
		return SeedIds.of(mockId);
	}

	private Instant minutesAgo(int minutes) {
		return clock.instant().minusSeconds(minutes * 60L);
	}

	/* ------------------------------------------------------------------ players */

	/** {@code roles}: positions per sport, as in the web app's seed. */
	private record Person(String id, String name, String handle, String tint, int phone, String avatar, String area,
			List<String> sports, Map<String, List<String>> roles) {
	}

	private static final List<Person> PEOPLE = List.of(
			new Person("u-kwame", "Kwame Asante", "kwame", "#e8e8e4", 1, "/avatars/kwame.jpg", "Osu, Accra", List.of("football", "basketball"),
					Map.of("football", List.of("forward"), "basketball", List.of("guard"))),
			new Person("u-kojo", "Kojo Mensah", "kojo", "#7c8a80", 2, "/avatars/kojo.jpg", "Labone, Accra", List.of("football"),
					Map.of("football", List.of("midfielder"))),
			new Person("u-abena", "Abena Owusu", "abena", "#e58f8f", 3, "/avatars/abena.jpg", "East Legon, Accra", List.of("basketball", "volleyball"),
					Map.of("basketball", List.of("guard"), "volleyball", List.of("setter"))),
			new Person("u-yaw", "Yaw Boateng", "yaw", "#c9a1d8", 4, "/avatars/yaw.jpg", "Tema", List.of("football"),
					Map.of("football", List.of("defender"))),
			new Person("u-ama", "Ama Serwaa", "ama", "#6b3a2e", 5, "/avatars/ama.jpg", "East Legon, Accra", List.of("basketball"),
					Map.of("basketball", List.of("guard"))),
			new Person("u-kofi", "Kofi Adjei", "kofi", "#b7d3c9", 6, null, "Osu, Accra", List.of("football"), Map.of("football", List.of("goalkeeper"))),
			new Person("u-esi", "Esi Appiah", "esi", "#f2d4a9", 7, null, "Cantonments, Accra", List.of("tennis", "volleyball"),
					Map.of("volleyball", List.of("outside-hitter"))),
			new Person("u-nii", "Nii Armah", "nii", "#a9c4f2", 8, null, "Labone, Accra", List.of("football"),
					Map.of("football", List.of("forward", "midfielder"))),
			new Person("u-akos", "Akosua Darko", "akos", "#d9b8e8", 9, null, "Tema", List.of("volleyball"), Map.of()),
			new Person("u-adwoa", "Adwoa Mensah", "adwoa", "#f5c9b3", 10, null, "Osu, Accra", List.of("football"), Map.of()),
			new Person("u-sam", "Samuel Tetteh", "sam", "#c7d8f0", 11, null, "East Legon, Accra", List.of("basketball"), Map.of()));

	/** The demo sign-in numbers, 024 000 00NN, so seeded players sign in through the ordinary flow. */
	static String demoPhone(int n) {
		return "+2332400000%02d".formatted(n);
	}

	private void users() {
		var created = at(-30, 9);
		for (var p : PEOPLE) {
			jdbc.sql("""
					INSERT INTO users (id, phone, country, name, handle, tint, avatar_url, area, sports, email, onboarded, terms_version,
					                   terms_accepted_at, created_at, updated_at)
					VALUES (:id, :phone, 'GH', :name, :handle, :tint, :avatar, :area, :sports, :email, true, :terms, :created, :created, :created)
					""")
				.param("id", id(p.id())).param("phone", demoPhone(p.phone())).param("name", p.name()).param("handle", p.handle())
				.param("tint", p.tint()).param("avatar", p.avatar()).param("area", p.area()).param("sports", p.sports().toArray(String[]::new))
				.param("email", p.handle() + "@example.com").param("terms", Terms.CURRENT).param("created", utc(created))
				.update();
			p.roles().forEach((sport, roles) -> {
				for (int rank = 0; rank < roles.size(); rank++) {
					jdbc.sql("INSERT INTO player_roles (user_id, sport, role, rank) VALUES (:user, :sport, :role, :rank)")
						.param("user", id(p.id())).param("sport", sport).param("role", roles.get(rank)).param("rank", rank)
						.update();
				}
			});
		}
	}

	/* ------------------------------------------------------------------ venues */

	private record Pitch(String id, String name, String sport, String format, String surface, long price) {
	}

	private record Venue(String id, String name, String area, String owner, String description, String address, String phone,
			List<Pitch> pitches, String[] hours, String[] amenities, String created) {
	}

	private static String[] everyDay(String open, String close) {
		var day = open + "-" + close;
		return new String[] { day, day, day, day, day, day, day };
	}

	private static final List<Venue> VENUES = List.of(
			new Venue("v-osu", "Halfway Line Turf", "Osu, Accra", "u-adwoa",
					"Two floodlit 5-a-side turfs off Oxford Street. Bibs and balls available at the gate.",
					"Behind Danquah Circle, Osu · GA-015-3451", "+233244100200",
					List.of(new Pitch("p-osu-a", "Pitch A", "football", "5-a-side", "turf", 25000),
							new Pitch("p-osu-b", "Pitch B", "football", "5-a-side", "turf", 25000),
							new Pitch("p-osu-7", "Big pitch", "football", "7-a-side", "turf", 35000)),
					everyDay("06:00", "23:00"), new String[] { "floodlights", "changing-rooms", "water", "equipment", "toilets" },
					"2026-01-10T09:00:00Z"),
			new Venue("v-legon", "Rebound Courts", "East Legon, Accra", "u-sam", "Outdoor basketball and volleyball courts, resurfaced this year.",
					"Lagos Avenue, East Legon", null,
					List.of(new Pitch("p-legon-b1", "Basketball court", "basketball", "5v5", "hard", 15000),
							new Pitch("p-legon-v1", "Volleyball court", "volleyball", "6v6", "sand", 12000)),
					everyDay("06:00", "21:00"), new String[] { "floodlights", "parking", "seating" }, "2026-02-02T09:00:00Z"),
			new Venue("v-cantonments", "Crossbar Sports Club", "Cantonments, Accra", "u-sam", "Members’ club with courts open to PlayChale bookings.",
					null, null,
					List.of(new Pitch("p-cant-t1", "Court 1", "tennis", "Doubles", "hard", 12000),
							new Pitch("p-cant-t2", "Court 2", "tennis", "Doubles", "hard", 12000),
							new Pitch("p-cant-f", "Football pitch", "football", "11-a-side", "grass", 60000)),
					// Closed Mondays
					new String[] { "07:00-20:00", "", "07:00-21:00", "07:00-21:00", "07:00-21:00", "07:00-21:00", "07:00-20:00" },
					new String[] { "changing-rooms", "showers", "parking", "toilets", "seating" }, "2026-03-15T09:00:00Z"),
			new Venue("v-tema", "Touchline Park", "Tema", "u-adwoa", null, null, null,
					List.of(new Pitch("p-tema-f", "Main pitch", "football", "5-a-side", "grass", 15000),
							new Pitch("p-tema-b", "Court", "basketball", "5v5", "hard", 10000)),
					everyDay("05:30", "20:00"), new String[] { "parking", "water" }, "2026-04-01T09:00:00Z"));

	private void venues() {
		for (var v : VENUES) {
			var created = Instant.parse(v.created());
			jdbc.sql("""
					INSERT INTO venues (id, owner_id, name, area, description, address, phone, country, currency, timezone, listed, hours, amenities, created_at, updated_at)
					VALUES (:id, :owner, :name, :area, :description, :address, :phone, 'GH', 'GHS', 'Africa/Accra', true, :hours, :amenities, :created, :created)
					""")
				.param("id", id(v.id())).param("owner", id(v.owner())).param("name", v.name()).param("area", v.area())
				.param("description", v.description()).param("address", v.address()).param("phone", v.phone())
				.param("hours", v.hours()).param("amenities", v.amenities()).param("created", utc(created))
				.update();
			for (int i = 0; i < v.pitches().size(); i++) {
				var p = v.pitches().get(i);
				jdbc.sql("""
						INSERT INTO pitches (id, venue_id, name, sport, format, surface, price_per_hour, position)
						VALUES (:id, :venue, :name, :sport, :format, :surface, :price, :position)
						""")
					.param("id", id(p.id())).param("venue", id(v.id())).param("name", p.name()).param("sport", p.sport())
					.param("format", p.format()).param("surface", p.surface()).param("price", p.price()).param("position", i)
					.update();
			}
		}
	}

	private static Venue venue(String id) {
		return VENUES.stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
	}

	/** The pitch a game at a venue is on: one sized for its format, else one for its sport, else the first. */
	private static Pitch pitchFor(Venue v, String sport, String format) {
		return v.pitches().stream().filter(p -> p.sport().equals(sport) && p.format().equals(format)).findFirst()
			.or(() -> v.pitches().stream().filter(p -> p.sport().equals(sport)).findFirst())
			.orElse(v.pitches().getFirst());
	}

	/* ------------------------------------------------------------------ games */

	/** A result as the mock writes it: sides and scorers by mock ID. */
	private record Result(int home, int away, List<String> homeSide, List<String> awaySide, Map<String, int[]> scorers,
			List<int[]> sets, String verifiedBy, Instant recordedAt, List<String> confirmedBy) {
	}

	private record Fixture(String competition, int round, String homeTeam, String awayTeam) {
	}

	private record Game(String id, String sport, String format, String title, Instant startsAt, int minutes, String venue,
			String unlistedName, String unlistedArea, int capacity, long cost, String host, String notes, List<String> players,
			Set<String> unpaid, String status, Result result, Fixture fixture, boolean bookPitch) {
	}

	private Game game(String id, String sport, String format, String title, Instant startsAt, int minutes, String venue, int capacity,
			long cost, String host, String notes, List<String> players) {
		return new Game(id, sport, format, title, startsAt, minutes, venue, null, null, capacity, cost, host, notes, players, Set.of(),
				null, null, null, true);
	}

	private Game unlisted(String id, String sport, String format, String title, Instant startsAt, int minutes, String name, String area,
			int capacity, long cost, String host, String notes, List<String> players) {
		return new Game(id, sport, format, title, startsAt, minutes, null, name, area, capacity, cost, host, notes, players, Set.of(),
				null, null, null, false);
	}

	private void games() {
		int sat = daysToSaturday();
		var list = new ArrayList<Game>();
		// Abena and Akosua haven't paid yet: the "collecting payments" demo.
		list.add(unpaid(game("g-osu-sat", "football", "5-a-side", "Saturday 5-a-side", at(sat, 18), 60, "v-osu", 10, 25000, "u-kojo",
				"Bring a light and a dark shirt. We start on time.", List.of("u-kojo", "u-kwame", "u-yaw", "u-kofi", "u-nii", "u-abena", "u-akos")),
				Set.of("u-abena", "u-akos")));
		list.add(game("g-legon-sun", "basketball", "5v5", "Pickup basketball", at(sat + 1, 16, 30), 90, "v-legon", 10, 15000, "u-abena", null,
				List.of("u-abena", "u-ama", "u-esi")));
		list.add(unlisted("g-labone-tonight", "football", "7-a-side", "After-work 7s", at(0, 20), 60, "Labone Astro", "Labone, Accra", 14, 28000,
				"u-nii", "Pitch is paid in cash at the gate by me — just pay your share here.",
				List.of("u-nii", "u-kofi", "u-yaw", "u-kojo", "u-kwame", "u-akos", "u-esi", "u-ama", "u-abena")));
		list.add(game("g-tema-tomorrow", "football", "5-a-side", "Tema morning run", at(1, 7), 60, "v-tema", 10, 0, "u-yaw",
				"Free game — just turn up. Early start so we beat the heat.", List.of("u-yaw", "u-akos")));
		list.add(game("g-tennis-weekend", "tennis", "Doubles", "Social doubles", at(sat, 9), 90, "v-cantonments", 4, 12000, "u-esi", null,
				List.of("u-esi", "u-abena", "u-ama", "u-akos")));
		list.add(unlisted("g-volley-next", "volleyball", "Beach 2v2", "Beach volley at Labadi", at(sat + 1, 15), 120, "Labadi Beach",
				"Labadi, Accra", 8, 0, "u-akos", null, List.of("u-akos", "u-esi")));
		list.add(game("g-osu-wed", "football", "5-a-side", "Midweek 5s", at(4, 19), 60, "v-osu", 10, 25000, "u-kwame", null,
				List.of("u-kwame", "u-kojo", "u-nii")));
		// Played two days ago, hosted by Kwame, result not recorded yet: the "record result" demo.
		list.add(game("g-osu-mon", "football", "5-a-side", "Monday night 5s", at(-2, 19), 60, "v-osu", 10, 25000, "u-kwame", null,
				List.of("u-kwame", "u-kojo", "u-yaw", "u-kofi", "u-nii", "u-abena")));
		// Completed with a verified result: Kwame's latest form and match history.
		var last = game("g-osu-last", "football", "5-a-side", "Friday 5s", recently(6, 18), 60, "v-osu", 10, 25000, "u-kojo", null,
				List.of("u-kojo", "u-kwame", "u-yaw", "u-kofi", "u-nii"));
		list.add(withResult(last, new Result(5, 3, List.of("u-kojo", "u-kwame", "u-kofi"), List.of("u-yaw", "u-nii"),
				Map.of("u-kwame", new int[] { 3, 0, 0 }, "u-kojo", new int[] { 2, 1, 0 }), List.of(), "u-kojo", recently(6, 20), List.of())));
		// Played three days ago, hosted by Kwame: a basketball result to record.
		list.add(game("g-legon-3x3", "basketball", "3x3", "Thursday 3x3", at(-3, 18), 60, "v-legon", 6, 15000, "u-kwame", null,
				List.of("u-kwame", "u-ama", "u-abena", "u-kofi", "u-nii", "u-yaw")));
		// Set-based results, so volleyball and tennis profiles show sets.
		var volley = unlisted("g-labadi-last", "volleyball", "Beach 2v2", "Sunday beach volley", recently(5, 15), 90, "Labadi Beach", "Labadi, Accra",
				4, 0, "u-abena", null, List.of("u-abena", "u-ama", "u-esi", "u-akos"));
		list.add(withResult(volley, new Result(2, 1, List.of("u-abena", "u-ama"), List.of("u-esi", "u-akos"), Map.of(),
				List.of(new int[] { 21, 17 }, new int[] { 18, 21 }, new int[] { 15, 12 }), "u-abena", recently(5, 17), List.of())));
		var tennis = unlisted("g-tennis-last", "tennis", "Singles", "Morning singles", recently(4, 8), 90, "Baseline Courts",
				"Achimota, Accra", 2, 8000, "u-esi", null, List.of("u-esi", "u-abena"));
		list.add(withResult(tennis, new Result(2, 1, List.of("u-esi"), List.of("u-abena"), Map.of(),
				List.of(new int[] { 6, 4 }, new int[] { 3, 6 }, new int[] { 7, 5 }), "u-esi", recently(4, 10), List.of())));
		list.forEach(this::insertGame);

		// The venue owners' own blocks, so their dashboards start with a real schedule.
		block("b-blk-1", "v-osu", "p-osu-a", at(0, 17), at(0, 18), "Tuesday regulars — pay cash", at(-5, 9));
		block("b-blk-2", "v-osu", "p-osu-b", at(1, 6), at(1, 8), "Turf maintenance", at(-5, 9));
		block("b-blk-3", "v-osu", "p-osu-7", at(2, 19), at(2, 21), "Corporate booking (invoice)", at(-4, 9));
		// Taken at the gate or on the phone, as in the web app's seed: one paid, one still owed.
		inPerson("b-inp-1", "v-osu", "p-osu-b", at(1, 20), at(1, 21), "Back Post Rangers", "+233241112222", 25000, "cash", at(-1, 12));
		inPerson("b-inp-2", "v-osu", "p-osu-7", at(2, 17), at(2, 18), "Offside Trap FC", null, 35000, null, at(-1, 15));
	}

	private static Game unpaid(Game g, Set<String> unpaid) {
		return new Game(g.id(), g.sport(), g.format(), g.title(), g.startsAt(), g.minutes(), g.venue(), g.unlistedName(), g.unlistedArea(),
				g.capacity(), g.cost(), g.host(), g.notes(), g.players(), unpaid, g.status(), g.result(), g.fixture(), g.bookPitch());
	}

	private static Game withResult(Game g, Result result) {
		return new Game(g.id(), g.sport(), g.format(), g.title(), g.startsAt(), g.minutes(), g.venue(), g.unlistedName(), g.unlistedArea(),
				g.capacity(), g.cost(), g.host(), g.notes(), g.players(), g.unpaid(), "completed", result, g.fixture(), g.bookPitch());
	}

	private void insertGame(Game g) {
		var created = at(-3, 10);
		String venueName;
		String venueArea;
		UUID pitchId = null;
		String pitchName = null;
		Pitch pitch = null;
		if (g.venue() != null) {
			var v = venue(g.venue());
			venueName = v.name();
			venueArea = v.area();
			if (g.bookPitch()) {
				pitch = pitchFor(v, g.sport(), g.format());
				pitchId = id(pitch.id());
				pitchName = pitch.name();
			}
		}
		else {
			venueName = g.unlistedName();
			venueArea = g.unlistedArea();
		}
		var status = g.status() != null ? g.status() : g.players().size() >= g.capacity() ? "full" : "open";
		jdbc.sql("""
				INSERT INTO games (id, sport, format, title, starts_at, duration_minutes, venue_kind, venue_id, venue_name, venue_area,
				                   pitch_id, pitch_name, capacity, total_cost, currency, visibility, host_id, notes, status,
				                   competition_id, fixture_round, home_team_id, away_team_id, created_at, updated_at)
				VALUES (:id, :sport, :format, :title, :starts, :minutes, :kind, :venue, :venueName, :venueArea, :pitch, :pitchName,
				        :capacity, :cost, :currency, 'public', :host, :notes, :status, :competition, :round, :homeTeam, :awayTeam, :created, :created)
				""")
			.param("id", id(g.id())).param("sport", g.sport()).param("format", g.format()).param("title", g.title())
			.param("starts", utc(g.startsAt())).param("minutes", g.minutes()).param("kind", g.venue() != null ? "listed" : "unlisted")
			.param("venue", g.venue() == null ? null : id(g.venue())).param("venueName", venueName).param("venueArea", venueArea)
			.param("pitch", pitchId).param("pitchName", pitchName).param("capacity", g.capacity()).param("cost", g.cost())
			.param("currency", CURRENCY).param("host", id(g.host())).param("notes", g.notes()).param("status", status)
			.param("competition", g.fixture() == null ? null : id(g.fixture().competition()))
			.param("round", g.fixture() == null ? null : g.fixture().round())
			.param("homeTeam", g.fixture() == null ? null : id(g.fixture().homeTeam()))
			.param("awayTeam", g.fixture() == null ? null : id(g.fixture().awayTeam()))
			.param("created", utc(created))
			.update();

		// Everyone joined two days ago, a second apart, so the roster keeps the mock's order.
		var joined = at(-2, 12);
		for (int i = 0; i < g.players().size(); i++) {
			var player = g.players().get(i);
			jdbc.sql("""
					INSERT INTO game_participants (id, game_id, user_id, joined_at, paid)
					VALUES (:id, :game, :user, :joined, :paid)
					""")
				.param("id", id(g.id() + "/" + player)).param("game", id(g.id())).param("user", id(player))
				.param("joined", utc(joined.plusSeconds(i))).param("paid", !g.unpaid().contains(player))
				.update();
		}

		if (pitch != null) {
			var ends = g.startsAt().plusSeconds(g.minutes() * 60L);
			jdbc.sql("""
					INSERT INTO bookings (id, venue_id, pitch_id, starts_at, ends_at, kind, game_id, booked_by, price, status, created_at)
					VALUES (:id, :venue, :pitch, :starts, :ends, 'game', :game, :host, :price, 'confirmed', :created)
					""")
				.param("id", id("b-" + g.id())).param("venue", id(g.venue())).param("pitch", pitchId).param("starts", utc(g.startsAt()))
				.param("ends", utc(ends)).param("game", id(g.id())).param("host", id(g.host()))
				.param("price", Math.round(pitch.price() * g.minutes() / 60.0)).param("created", utc(created))
				.update();
		}

		if (g.result() != null) {
			insertResult(g.id(), g.result());
		}
	}

	private void insertResult(String gameId, Result r) {
		jdbc.sql("INSERT INTO game_results (game_id, home_score, away_score, recorded_by, recorded_at) VALUES (:game, :home, :away, :by, :at)")
			.param("game", id(gameId)).param("home", r.home()).param("away", r.away()).param("by", id(r.verifiedBy()))
			.param("at", utc(r.recordedAt()))
			.update();
		var sides = new ArrayList<String[]>();
		r.homeSide().forEach(p -> sides.add(new String[] { p, "home" }));
		r.awaySide().forEach(p -> sides.add(new String[] { p, "away" }));
		for (var side : sides) {
			var stats = r.scorers().getOrDefault(side[0], new int[] { 0, 0, 0 });
			jdbc.sql("""
					INSERT INTO result_players (game_id, player_key, user_id, side, goals, assists, points)
					VALUES (:game, :key, :user, :side, :goals, :assists, :points)
					""")
				.param("game", id(gameId)).param("key", id(side[0]).toString()).param("user", id(side[0])).param("side", side[1])
				.param("goals", stats[0]).param("assists", stats[1]).param("points", stats[2])
				.update();
		}
		for (int i = 0; i < r.sets().size(); i++) {
			jdbc.sql("INSERT INTO result_sets (game_id, number, home, away) VALUES (:game, :number, :home, :away)")
				.param("game", id(gameId)).param("number", i + 1).param("home", r.sets().get(i)[0]).param("away", r.sets().get(i)[1])
				.update();
		}
		for (var confirmed : r.confirmedBy()) {
			jdbc.sql("INSERT INTO result_confirmations (game_id, user_id, confirmed_at) VALUES (:game, :user, :at)")
				.param("game", id(gameId)).param("user", id(confirmed)).param("at", utc(r.recordedAt().plusSeconds(3600)))
				.update();
		}
	}

	private void block(String id, String venue, String pitch, Instant starts, Instant ends, String note, Instant created) {
		jdbc.sql("""
				INSERT INTO bookings (id, venue_id, pitch_id, starts_at, ends_at, kind, booked_by, price, note, status, created_at)
				VALUES (:id, :venue, :pitch, :starts, :ends, 'block', :owner, 0, :note, 'confirmed', :created)
				""")
			.param("id", id(id)).param("venue", id(venue)).param("pitch", id(pitch)).param("starts", utc(starts)).param("ends", utc(ends))
			.param("owner", id(venue(venue).owner())).param("note", note).param("created", utc(created))
			.update();
	}

	private void inPerson(String id, String venue, String pitch, Instant starts, Instant ends, String customer, String phone, long price,
			String paidVia, Instant created) {
		jdbc.sql("""
				INSERT INTO bookings (id, venue_id, pitch_id, starts_at, ends_at, kind, booked_by, price, customer_name, customer_phone, paid_via,
				                      status, created_at)
				VALUES (:id, :venue, :pitch, :starts, :ends, 'in-person', :owner, :price, :customer, :phone, :paid, 'confirmed', :created)
				""")
			.param("id", id(id)).param("venue", id(venue)).param("pitch", id(pitch)).param("starts", utc(starts)).param("ends", utc(ends))
			.param("owner", id(venue(venue).owner())).param("price", price).param("customer", customer).param("phone", phone)
			.param("paid", paidVia).param("created", utc(created))
			.update();
	}

	/* ------------------------------------------------------------------ a league in progress */

	private record Team(String id, String name, String captain, List<String> players, String tint) {
	}

	private static final List<Team> TEAMS = List.of(
			new Team("t-ballers", "Osu Ballers", "u-kojo", List.of("u-kojo", "u-kwame", "u-kofi"), "#7cf0c8"),
			new Team("t-rovers", "Tema Rovers", "u-yaw", List.of("u-yaw", "u-nii"), "#a9c4f2"),
			new Team("t-hoopers", "Legon Hoopers", "u-abena", List.of("u-abena", "u-ama"), "#f2d4a9"),
			new Team("t-labone", "Labone United", "u-esi", List.of("u-esi", "u-akos"), "#d9b8e8"));

	private static final Team CREW = new Team("t-friday", "Friday Fives", "u-kwame", List.of("u-kwame", "u-yaw", "u-nii", "u-kofi"), "#f5c9b3");

	/** A team on its own: its captain and members (the teams module's tables). */
	private void standingTeam(Team t, Instant created) {
		jdbc.sql("""
				INSERT INTO teams (id, name, captain_id, tint, join_token, created_at)
				VALUES (:id, :name, :captain, :tint, :token, :created)
				""")
			.param("id", id(t.id())).param("name", t.name()).param("captain", id(t.captain()))
			.param("tint", t.tint()).param("token", t.id().replace("t-", "") + "-squad").param("created", utc(created))
			.update();
		for (int i = 0; i < t.players().size(); i++) {
			jdbc.sql("INSERT INTO team_members (team_id, user_id, joined_at) VALUES (:team, :user, :joined)")
				.param("team", id(t.id())).param("user", id(t.players().get(i))).param("joined", utc(created.plusSeconds(i)))
				.update();
		}
	}

	private static Team team(String id) {
		return TEAMS.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
	}

	/** Four teams, the first round played, the second to come: so the table and fixtures both have something. */
	private void league() {
		var league = "c-osu-estate";
		var osu = venue("v-osu");
		jdbc.sql("""
				INSERT INTO competitions (id, name, sport, format, organiser_id, venue_kind, venue_id, venue_name, venue_area, starts_at,
				                          duration_minutes, status, created_at, updated_at)
				VALUES (:id, 'Osu Estate League', 'football', '5-a-side', :organiser, 'listed', :venue, :venueName, :venueArea, :starts, 60,
				        'running', :created, :created)
				""")
			.param("id", id(league)).param("organiser", id("u-kojo")).param("venue", id("v-osu")).param("venueName", osu.name())
			.param("venueArea", osu.area()).param("starts", utc(at(-7, 17))).param("created", utc(at(-21, 9)))
			.update();
		for (var t : TEAMS) {
			var created = at(-20, 9);
			standingTeam(t, created);
			jdbc.sql("INSERT INTO competition_entries (competition_id, team_id, status, entered_at) VALUES (:league, :team, 'entered', :at)")
				.param("league", id(league)).param("team", id(t.id())).param("at", utc(created))
				.update();
			for (int i = 0; i < t.players().size(); i++) {
				jdbc.sql("INSERT INTO entry_players (team_id, competition_id, user_id, added_at) VALUES (:team, :league, :user, :added)")
					.param("team", id(t.id())).param("league", id(league)).param("user", id(t.players().get(i)))
					.param("added", utc(created.plusSeconds(i)))
					.update();
			}
		}
		// A team outside any league, as in the web app's seed: friends who play on Fridays.
		standingTeam(CREW, at(-40, 9));
		int sat = daysToSaturday();
		fixture(league, "g-league-1a", 1, "t-ballers", "t-rovers", at(-7, 17), new Result(3, 1, team("t-ballers").players(),
				team("t-rovers").players(), Map.of("u-kwame", new int[] { 2, 0, 0 }, "u-kojo", new int[] { 1, 1, 0 }), List.of(), "u-kojo",
				at(-7, 19), List.of("u-kwame", "u-yaw")));
		fixture(league, "g-league-1b", 1, "t-hoopers", "t-labone", at(-7, 18), new Result(2, 2, team("t-hoopers").players(),
				team("t-labone").players(), Map.of("u-abena", new int[] { 1, 0, 0 }, "u-esi", new int[] { 2, 0, 0 }), List.of(), "u-kojo",
				at(-7, 20), List.of("u-esi")));
		fixture(league, "g-league-2a", 2, "t-rovers", "t-hoopers", at(sat, 17), null);
		fixture(league, "g-league-2b", 2, "t-labone", "t-ballers", at(sat, 18), null);
	}

	private void fixture(String league, String id, int round, String home, String away, Instant startsAt, Result result) {
		var h = team(home);
		var a = team(away);
		var both = new LinkedHashSet<String>(h.players());
		both.addAll(a.players());
		var squad = List.copyOf(both);
		insertGame(new Game(id, "football", "5-a-side", h.name() + " vs " + a.name(), startsAt, 60, "v-osu", null, null, squad.size(), 0, "u-kojo",
				null, squad, Set.of(), result == null ? "full" : "completed", result, new Fixture(league, round, home, away), false));
	}

	/* ------------------------------------------------------------------ corporate league */

	/** The eight companies, with the players their managers put forward. */
	private static final List<Team> COMPANIES = List.of(
			new Team("t-apex", "Apex Ltd", "u-kwame", List.of(), "#7cf0c8"),
			new Team("t-birim", "Birim Bank", "u-kojo", List.of(), "#a9c4f2"),
			new Team("t-coast", "Coast Telecom", "u-kojo", List.of(), "#f2d4a9"),
			new Team("t-densu", "Densu Energy", "u-kojo", List.of(), "#d9b8e8"),
			new Team("t-enyo", "Enyo Foods", "u-kojo", List.of(), "#b7d3c9"),
			new Team("t-frontier", "Frontier Insurance", "u-kojo", List.of(), "#f5c9b3"),
			new Team("t-gold", "Bluefinch Mining", "u-kojo", List.of(), "#c9a1d8"),
			new Team("t-harbour", "Harbour Logistics", "u-kojo", List.of(), "#e8e8e4"));

	/**
	 * Fifteen to a squad, which is what a five-a-side company side actually registers. Composed from
	 * two name pools by the same rule the web app's demo uses, so both show the same hundred and
	 * twenty people without either having to write them out.
	 */
	private static final List<String> FIRST_NAMES = List.of("Kofi", "Yaw", "Nana", "Kwesi", "Michael", "Samuel", "Isaac", "Daniel",
			"Emmanuel", "Joseph", "Prince", "Richard", "Felix", "Bright", "Eric", "Stephen", "Jonathan", "Patrick", "Godfred",
			"Ebenezer", "Abena", "Akosua", "Adwoa", "Efua", "Ama", "Nii", "Solomon", "Gideon", "Alfred", "Kwabena");

	private static final List<String> LAST_NAMES = List.of("Asare", "Darko", "Owusu", "Boateng", "Tetteh", "Adjei", "Mensah", "Ofori",
			"Quaye", "Larbi", "Amoah", "Danso", "Nyarko", "Agyeman", "Baidoo", "Kusi", "Appiah", "Okine", "Annan", "Sowah",
			"Frimpong", "Boakye", "Sarpong", "Gyamfi", "Nartey", "Armah", "Antwi", "Tagoe", "Mireku", "Doe");

	private static final int SQUAD_SIZE = 15;

	/** Two pitches, so a round is played in two places at once and a clash is a real possibility. */
	private record Place(String id, String name, String area) {
	}

	private static final List<Place> LOCATIONS = List.of(
			new Place("loc-accra-sports-park", "Penalty Spot Park", "Airport Residential, Accra"),
			new Place("loc-aviation-centre", "Far Post Arena", "Airport, Accra"));

	/**
	 * A hundred and twenty people from two pools of thirty, all of them different. The surname is
	 * chosen so each first name is paired with a different one every time it comes round: two sides of
	 * the same match used to share nine names, which reads as a bug to anyone being shown the demo.
	 * The web app's seed does exactly the same (webapp/app/services/mock/seed-corporate.ts).
	 */
	private static List<String> squadFor(int companyIndex) {
		var squad = new ArrayList<String>();
		for (int i = 0; i < SQUAD_SIZE; i++) {
			var nth = companyIndex * SQUAD_SIZE + i;
			var first = Math.floorMod(nth, FIRST_NAMES.size());
			var last = Math.floorMod(11 * (nth / FIRST_NAMES.size()) + 3 * first, LAST_NAMES.size());
			squad.add(FIRST_NAMES.get(first) + " " + LAST_NAMES.get(last));
		}
		return List.copyOf(squad);
	}

	/** Rounds already played; the last is still to come, so an operator has something to run. */
	private static final int PLAYED_ROUNDS = 4;

	private static final String WORKSPACE = "org-accra-games";

	private static final String CORPORATE_LEAGUE = "c-inter-company";

	private static final String OPERATOR = "u-kojo";

	/** Apex is run by a demo account, so its roster screens have someone to sign in as. */
	private static final String APEX_MANAGER = "u-kwame";

	/**
	 * A company league mid-season, matching the web app's demo data so the same story can be shown
	 * against either. Six company sides, rosters their managers attested to and the organiser
	 * reviewed, a published schedule with results in, entry fees part collected, and an audit trail.
	 */
	private void corporateLeague() {
		var created = at(-40, 9);
		jdbc.sql("""
				INSERT INTO organisations (id, name, slug, country, primary_colour, corporate_enabled, created_by, created_at, updated_at)
				VALUES (:id, 'Overlap Corporate Sports', 'overlap-corporate-sports', 'GH', '#0c3a3a', true, :owner, :created, :created)
				""").param("id", id(WORKSPACE)).param("owner", id(OPERATOR)).param("created", utc(created)).update();
		member(OPERATOR, "owner", created);
		member("u-abena", "admin", at(-38, 9));
		// The two referees hold a seat that lets them be put on a fixture and nothing else: they see
		// their own match sheets, and none of the league's money or any company's roster.
		member("u-yaw", "official", at(-32, 9));
		member("u-nii", "official", at(-32, 9));

		jdbc.sql("""
				INSERT INTO competitions (id, name, sport, format, organiser_id, organisation_id, schedule_status, player_lists, venue_kind,
				                          venue_name, venue_area, starts_at, duration_minutes, status, created_at, updated_at)
				VALUES (:id, 'Inter-Company League 2026', 'football', '5-a-side', :organiser, :organisation, 'published', 'optional',
				        'unlisted', 'Penalty Spot Park', 'Airport Residential, Accra', :starts, 60, 'running', :created, :created)
				""")
			.param("id", id(CORPORATE_LEAGUE)).param("organiser", id(OPERATOR)).param("organisation", id(WORKSPACE))
			.param("starts", utc(at(-28, 18))).param("created", utc(at(-35, 9)))
			.update();

		for (var place : LOCATIONS) {
			jdbc.sql("""
					INSERT INTO competition_locations (id, competition_id, name, area, created_at)
					VALUES (:id, :competition, :name, :area, :created)
					""").param("id", id(place.id())).param("competition", id(CORPORATE_LEAGUE)).param("name", place.name())
				.param("area", place.area()).param("created", utc(at(-30, 9)))
				.update();
		}

		for (var company : COMPANIES) {
			enterCompany(company);
		}
		corporateFixtures();
		corporateAudit();
	}

	private void member(String user, String role, Instant joined) {
		jdbc.sql("""
				INSERT INTO organisation_memberships (organisation_id, user_id, role, created_at, created_by)
				VALUES (:organisation, :user, :role, :joined, :owner)
				""").param("organisation", id(WORKSPACE)).param("user", id(user)).param("role", role)
			.param("joined", utc(joined)).param("owner", id(OPERATOR)).update();
	}

	/** A company enters as itself: no squad of PlayChale players, a manager, and a roster of names. */
	private void enterCompany(Team company) {
		var entered = at(-30, 9);
		standingTeam(company, entered);
		jdbc.sql("INSERT INTO competition_entries (competition_id, team_id, status, entered_at) VALUES (:league, :team, 'entered', :at)")
			.param("league", id(CORPORATE_LEAGUE)).param("team", id(company.id())).param("at", utc(entered)).update();

		var manager = company.id().equals("t-apex") ? APEX_MANAGER : OPERATOR;
		jdbc.sql("""
				INSERT INTO competition_entry_managers (competition_id, team_id, user_id, added_by, added_at)
				VALUES (:league, :team, :user, :by, :at)
				""").param("league", id(CORPORATE_LEAGUE)).param("team", id(company.id())).param("user", id(manager))
			.param("by", id(OPERATOR)).param("at", utc(entered)).update();

		var players = squadFor(COMPANIES.indexOf(company));
		for (int i = 0; i < players.size(); i++) {
			// Frontier is still waiting on review, and one of Enyo's was turned down: the queue has
			// something in it, and a review decision has a reason attached.
			var awaiting = company.id().equals("t-frontier");
			var turnedDown = company.id().equals("t-enyo") && i == 4;
			var state = awaiting ? "submitted" : turnedDown ? "rejected" : "approved";
			jdbc.sql("""
					INSERT INTO roster_members (id, competition_id, team_id, display_name, employee_reference, user_id, eligibility_state,
					                            attested_by, attested_at, reviewed_by, reviewed_at, review_note, created_by, created_at, updated_at)
					VALUES (:id, :league, :team, :name, :reference, :user, :state, :attestedBy, :attestedAt, :reviewedBy, :reviewedAt,
					        :note, :by, :created, :created)
					""")
				.param("id", id("rm-" + company.id() + "-" + (i + 1))).param("league", id(CORPORATE_LEAGUE)).param("team", id(company.id()))
				.param("name", players.get(i))
				.param("reference", company.name().substring(0, 3).toUpperCase(Locale.ROOT) + "-%03d".formatted(i + 1))
				// One player has joined PlayChale and picked up their place; the rest are names on a list.
				.param("user", company.id().equals("t-apex") && i == 0 ? id(APEX_MANAGER) : null)
				.param("state", state).param("attestedBy", id(manager)).param("attestedAt", utc(at(-26, 11)))
				.param("reviewedBy", awaiting ? null : id(OPERATOR)).param("reviewedAt", awaiting ? null : utc(at(-25, 9)))
				.param("note", turnedDown ? "Not on the payroll for this quarter." : null)
				.param("by", id(manager)).param("created", utc(at(-27, 10)))
				.update();
		}

		var index = COMPANIES.indexOf(company);
		jdbc.sql("""
				INSERT INTO competition_entry_finance (competition_id, team_id, amount_due, status, method, reference, paid_at, private_note,
				                                       updated_by, updated_at)
				VALUES (:league, :team, 150000, :status, :method, :reference, :paidAt, :note, :by, :at)
				""")
			.param("league", id(CORPORATE_LEAGUE)).param("team", id(company.id()))
			.param("status", index < 4 ? "paid" : index == 4 ? "waived" : "unpaid")
			.param("method", index < 4 ? (index % 2 == 1 ? "momo" : "bank-transfer") : null)
			.param("reference", index < 4 ? "INV-2026-%03d".formatted(index + 1) : null)
			.param("paidAt", index < 4 ? utc(at(-24 + index, 12)) : null)
			.param("note", index == 4 ? "Sponsor in kind — pitch hire for round three." : null)
			.param("by", id(OPERATOR)).param("at", utc(at(-24 + index, 12)))
			.update();
	}

	/** Round-robin by the circle method, the same pairings the draw makes. */
	private void corporateFixtures() {
		var order = new ArrayList<>(COMPANIES.stream().map(Team::id).toList());
		var half = order.size() / 2;
		for (int round = 0; round < order.size() - 1; round++) {
			for (int slot = 0; slot < half; slot++) {
				var first = order.get(slot);
				var second = order.get(order.size() - 1 - slot);
				var home = round % 2 == 1 ? second : first;
				var away = round % 2 == 1 ? first : second;
				var played = round < PLAYED_ROUNDS;
				// The same scores as the web app's demo, so the two tables agree.
				var homeScore = (round * 3 + slot * 2) % 5;
				var awayScore = (round + slot * 3) % 4;
				var gameId = "g-ic-" + (round + 1) + "-" + (slot + 1);
				var result = played ? new Result(homeScore, awayScore, List.of(), List.of(), Map.of(), List.of(), OPERATOR,
						at(-28 + round * 7, 20), List.of()) : null;
				var place = LOCATIONS.get(slot % LOCATIONS.size());
				insertGame(new Game(gameId, "football", "5-a-side", team(COMPANIES, home).name() + " vs " + team(COMPANIES, away).name(),
						at(-28 + round * 7, 18 + slot / LOCATIONS.size()), 60, null, place.name(), place.area(), 2, 0, OPERATOR,
						null, List.of(), Set.of(), played ? "completed" : "full", result, new Fixture(CORPORATE_LEAGUE, round + 1, home, away),
						false));
				// One official per pitch: the same person can't referee two fixtures at once.
				jdbc.sql("""
						INSERT INTO fixture_officials (game_id, user_id, assigned_by, assigned_at)
						VALUES (:game, :user, :by, :at)
						""").param("game", id(gameId)).param("user", id(slot % LOCATIONS.size() == 0 ? "u-yaw" : "u-nii"))
					.param("by", id(OPERATOR)).param("at", utc(at(-27, 9))).update();
				if (played) {
					matchSheet(gameId, home, away, homeScore, awayScore, at(-28 + round * 7, 20));
				}
			}
			order.add(1, order.remove(order.size() - 1));
		}
	}

	/**
	 * The official sheet behind a result that is already in. Without these the league's scorers chart
	 * has nothing to show: a company's players are a staff list, so the result tables — which only
	 * know PlayChale accounts — know none of them. The goals add up to the score that was recorded.
	 */
	private void matchSheet(String gameId, String home, String away, int homeScore, int awayScore, Instant when) {
		jdbc.sql("""
				INSERT INTO fixture_match_sheets (game_id, home_score, away_score, status, saved_by, saved_at, submitted_by, submitted_at, version)
				VALUES (:game, :home, :away, 'submitted', :by, :at, :by, :at, 1)
				""").param("game", id(gameId)).param("home", homeScore).param("away", awayScore).param("by", id(OPERATOR))
			.param("at", utc(when)).update();
		sheetSide(gameId, home, homeScore);
		sheetSide(gameId, away, awayScore);
	}

	/**
	 * One goal each to the first few on the sheet, so they add up to the score and the same handful
	 * lead the chart across the season, as they would in a real company league. A company still
	 * awaiting review, and the one player turned down, are not eligible and so are not on it.
	 */
	private void sheetSide(String gameId, String teamId, int goals) {
		if (teamId.equals("t-frontier")) {
			return;
		}
		var squad = squadFor(COMPANIES.indexOf(team(COMPANIES, teamId)));
		for (int i = 0; i < squad.size(); i++) {
			if (teamId.equals("t-enyo") && i == 4) {
				continue;
			}
			jdbc.sql("""
					INSERT INTO match_sheet_players (game_id, roster_member_id, team_id, participation, checked_in, goals, assists)
					VALUES (:game, :member, :team, :participation, :checkedIn, :goals, :assists)
					""")
				.param("game", id(gameId)).param("member", id("rm-" + teamId + "-" + (i + 1))).param("team", id(teamId))
				.param("participation", i < 5 ? "starter" : "substitute").param("checkedIn", i < 8)
				.param("goals", i < goals ? 1 : 0).param("assists", goals > 0 && i == goals ? 1 : 0)
				.update();
		}
	}

	private void corporateAudit() {
		audit("ae-schedule-generated", "schedule.generated", "competition", id(CORPORATE_LEAGUE), "15 draft fixtures", at(-29, 9));
		audit("ae-roster-submitted", "roster.submitted", "team", id("t-apex"), "5 players attested", at(-26, 11));
		audit("ae-roster-approved", "eligibility.approved", "team", id("t-apex"), "5 players", at(-25, 9));
		audit("ae-roster-rejected", "eligibility.rejected", "team", id("t-enyo"), "1 player", at(-25, 10));
		audit("ae-schedule-published", "schedule.published", "competition", id(CORPORATE_LEAGUE), "15 fixtures", at(-28, 12));
		audit("ae-finance-updated", "finance.updated", "team", id("t-apex"), "paid 150000", at(-24, 12));
	}

	private void audit(String eventId, String type, String subjectType, UUID subject, String details, Instant at) {
		jdbc.sql("""
				INSERT INTO audit_events (id, organisation_id, competition_id, actor_id, event_type, subject_type, subject_id, details, occurred_at)
				VALUES (:id, :organisation, :competition, :actor, :type, :subjectType, :subject, CAST(:details AS jsonb), :at)
				""")
			.param("id", id(eventId)).param("organisation", id(WORKSPACE)).param("competition", id(CORPORATE_LEAGUE))
			.param("actor", id(OPERATOR)).param("type", type).param("subjectType", subjectType).param("subject", subject)
			.param("details", "{\"note\":\"%s\"}".formatted(details)).param("at", utc(at))
			.update();
	}

	private static Team team(List<Team> teams, String id) {
		return teams.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
	}

	/* ------------------------------------------------------------------ notifications */

	private void notifications() {
		note("u-abena", "payment-reminder", "Pay your GH₵ 25 share", "Kojo is collecting for Saturday 5-a-side.",
				"/games/%s?pay=1".formatted(id("g-osu-sat")), "u-kojo", minutesAgo(95), false);
		note("u-abena", "result-added", "Result: Morning singles", "You lost 1–2 in sets. Your stats are updated.",
				"/games/%s".formatted(id("g-tennis-last")), "u-esi", minutesAgo(60 * 24 * 4 - 120), true);
		note("u-kwame", "player-joined", "Nii joined Midweek 5s", "3 of 10 spots filled.", "/games/%s".formatted(id("g-osu-wed")), "u-nii",
				minutesAgo(180), false);
		note("u-kwame", "payment-received", "Kojo paid GH₵ 25", "Midweek 5s · 3 of 3 paid", "/games/%s".formatted(id("g-osu-wed")), "u-kojo",
				minutesAgo(60 * 26), true);
		note("u-kwame", "result-added", "Result: Friday 5s", "You won 5–3. Your stats are updated.", "/games/%s".formatted(id("g-osu-last")),
				"u-kojo", minutesAgo(60 * 24 * 6 - 120), true);
		note("u-kojo", "payment-received", "Kwame paid GH₵ 25", "Saturday 5-a-side · 5 of 7 paid", "/games/%s".formatted(id("g-osu-sat")),
				"u-kwame", minutesAgo(130), false);
		note("u-adwoa", "booking", "Kwame booked Pitch A", "Midweek 5s · Halfway Line Turf", "/venues/%s/manage".formatted(id("v-osu")), "u-kwame",
				minutesAgo(60 * 20), false);
	}

	private void note(String user, String kind, String title, String body, String link, String actor, Instant at, boolean read) {
		jdbc.sql("""
				INSERT INTO notifications (id, user_id, kind, title, body, link, actor_id, created_at, read_at)
				VALUES (gen_random_uuid(), :user, :kind, :title, :body, :link, :actor, :at, :read)
				""")
			.param("user", id(user)).param("kind", kind).param("title", title).param("body", body).param("link", link)
			.param("actor", id(actor)).param("at", utc(at)).param("read", read ? utc(at) : null)
			.update();
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
