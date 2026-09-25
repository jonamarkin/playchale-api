package com.playchale.api.devsupport.internal.service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.playchale.api.market.Market;
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
			new DemoAccount("u-kwame", "Football and basketball · has results to add"),
			new DemoAccount("u-kojo", "Hosts Saturday 5-a-side · collecting payments"),
			new DemoAccount("u-abena", "Three sports · owes a share"),
			new DemoAccount("u-adwoa", "Venue owner · Osu Astro Turf"),
			new DemoAccount("u-sam", "Venue owner · East Legon Courts"));

	void load() {
		now = clock.instant().atZone(Market.get(Market.DEFAULT).zone());
		users();
		venues();
		games();
		league();
		notifications();
	}

	/* ------------------------------------------------------------------ times and ids */

	/** {@code days} from today at hh:mm local time. */
	private Instant at(int days, int hours, int minutes) {
		return now.toLocalDate().plusDays(days).atTime(hours, minutes).atZone(now.getZone()).toInstant();
	}

	private Instant at(int days, int hours) {
		return at(days, hours, 0);
	}

	/** Days to the next Saturday, or 0 if it's Saturday. */
	private int daysToSaturday() {
		int jsDay = now.getDayOfWeek().getValue() % 7; // Sunday 0 ... Saturday 6, like JavaScript
		return (6 - jsDay + 7) % 7;
	}

	private static UUID id(String mockId) {
		return SeedIds.of(mockId);
	}

	private Instant minutesAgo(int minutes) {
		return clock.instant().minusSeconds(minutes * 60L);
	}

	/* ------------------------------------------------------------------ players */

	private record Person(String id, String name, String handle, String tint, int phone, String avatar, String area, String position,
			List<String> sports) {
	}

	private static final List<Person> PEOPLE = List.of(
			new Person("u-kwame", "Kwame Asante", "kwame", "#e8e8e4", 1, "/avatars/kwame.jpg", "Osu, Accra", "Striker", List.of("football", "basketball")),
			new Person("u-kojo", "Kojo Mensah", "kojo", "#7c8a80", 2, "/avatars/kojo.jpg", "Labone, Accra", "Midfielder", List.of("football")),
			new Person("u-abena", "Abena Owusu", "abena", "#e58f8f", 3, "/avatars/abena.jpg", "East Legon, Accra", "Guard", List.of("basketball", "volleyball")),
			new Person("u-yaw", "Yaw Boateng", "yaw", "#c9a1d8", 4, "/avatars/yaw.jpg", "Tema", "Defender", List.of("football")),
			new Person("u-ama", "Ama Serwaa", "ama", "#6b3a2e", 5, "/avatars/ama.jpg", "East Legon, Accra", "Point guard", List.of("basketball")),
			new Person("u-kofi", "Kofi Adjei", "kofi", "#b7d3c9", 6, null, "Osu, Accra", "Goalkeeper", List.of("football")),
			new Person("u-esi", "Esi Appiah", "esi", "#f2d4a9", 7, null, "Cantonments, Accra", null, List.of("tennis", "volleyball")),
			new Person("u-nii", "Nii Armah", "nii", "#a9c4f2", 8, null, "Labone, Accra", "Winger", List.of("football")),
			new Person("u-akos", "Akosua Darko", "akos", "#d9b8e8", 9, null, "Tema", null, List.of("volleyball")),
			new Person("u-adwoa", "Adwoa Mensah", "adwoa", "#f5c9b3", 10, null, "Osu, Accra", "Venue owner", List.of("football")),
			new Person("u-sam", "Samuel Tetteh", "sam", "#c7d8f0", 11, null, "East Legon, Accra", "Venue owner", List.of("basketball")));

	/** The demo sign-in numbers, 024 000 00NN, so seeded players sign in through the ordinary flow. */
	static String demoPhone(int n) {
		return "+2332400000%02d".formatted(n);
	}

	private void users() {
		var created = at(-30, 9);
		for (var p : PEOPLE) {
			jdbc.sql("""
					INSERT INTO users (id, phone, country, name, handle, tint, avatar_url, area, sports, position, onboarded, created_at, updated_at)
					VALUES (:id, :phone, 'GH', :name, :handle, :tint, :avatar, :area, :sports, :position, true, :created, :created)
					""")
				.param("id", id(p.id())).param("phone", demoPhone(p.phone())).param("name", p.name()).param("handle", p.handle())
				.param("tint", p.tint()).param("avatar", p.avatar()).param("area", p.area()).param("sports", p.sports().toArray(String[]::new))
				.param("position", p.position()).param("created", utc(created))
				.update();
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
			new Venue("v-osu", "Osu Astro Turf", "Osu, Accra", "u-adwoa",
					"Two floodlit 5-a-side turfs off Oxford Street. Bibs and balls available at the gate.",
					"Behind Danquah Circle, Osu · GA-015-3451", "+233244100200",
					List.of(new Pitch("p-osu-a", "Pitch A", "football", "5-a-side", "turf", 25000),
							new Pitch("p-osu-b", "Pitch B", "football", "5-a-side", "turf", 25000),
							new Pitch("p-osu-7", "Big pitch", "football", "7-a-side", "turf", 35000)),
					everyDay("06:00", "23:00"), new String[] { "floodlights", "changing-rooms", "water", "equipment", "toilets" },
					"2026-01-10T09:00:00Z"),
			new Venue("v-legon", "East Legon Courts", "East Legon, Accra", "u-sam", "Outdoor basketball and volleyball courts, resurfaced this year.",
					"Lagos Avenue, East Legon", null,
					List.of(new Pitch("p-legon-b1", "Basketball court", "basketball", "5v5", "hard", 15000),
							new Pitch("p-legon-v1", "Volleyball court", "volleyball", "6v6", "sand", 12000)),
					everyDay("06:00", "21:00"), new String[] { "floodlights", "parking", "seating" }, "2026-02-02T09:00:00Z"),
			new Venue("v-cantonments", "Cantonments Sports Club", "Cantonments, Accra", "u-sam", "Members’ club with courts open to PlayChale bookings.",
					null, null,
					List.of(new Pitch("p-cant-t1", "Court 1", "tennis", "Doubles", "hard", 12000),
							new Pitch("p-cant-t2", "Court 2", "tennis", "Doubles", "hard", 12000),
							new Pitch("p-cant-f", "Football pitch", "football", "11-a-side", "grass", 60000)),
					// Closed Mondays
					new String[] { "07:00-20:00", "", "07:00-21:00", "07:00-21:00", "07:00-21:00", "07:00-21:00", "07:00-20:00" },
					new String[] { "changing-rooms", "showers", "parking", "toilets", "seating" }, "2026-03-15T09:00:00Z"),
			new Venue("v-tema", "Community 11 Park", "Tema", "u-adwoa", null, null, null,
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
		var last = game("g-osu-last", "football", "5-a-side", "Friday 5s", at(-6, 18), 60, "v-osu", 10, 25000, "u-kojo", null,
				List.of("u-kojo", "u-kwame", "u-yaw", "u-kofi", "u-nii"));
		list.add(withResult(last, new Result(5, 3, List.of("u-kojo", "u-kwame", "u-kofi"), List.of("u-yaw", "u-nii"),
				Map.of("u-kwame", new int[] { 3, 0, 0 }, "u-kojo", new int[] { 2, 1, 0 }), List.of(), "u-kojo", at(-6, 20), List.of())));
		// Played three days ago, hosted by Kwame: a basketball result to record.
		list.add(game("g-legon-3x3", "basketball", "3x3", "Thursday 3x3", at(-3, 18), 60, "v-legon", 6, 15000, "u-kwame", null,
				List.of("u-kwame", "u-ama", "u-abena", "u-kofi", "u-nii", "u-yaw")));
		// Set-based results, so volleyball and tennis profiles show sets.
		var volley = unlisted("g-labadi-last", "volleyball", "Beach 2v2", "Sunday beach volley", at(-5, 15), 90, "Labadi Beach", "Labadi, Accra",
				4, 0, "u-abena", null, List.of("u-abena", "u-ama", "u-esi", "u-akos"));
		list.add(withResult(volley, new Result(2, 1, List.of("u-abena", "u-ama"), List.of("u-esi", "u-akos"), Map.of(),
				List.of(new int[] { 21, 17 }, new int[] { 18, 21 }, new int[] { 15, 12 }), "u-abena", at(-5, 17), List.of())));
		var tennis = unlisted("g-tennis-last", "tennis", "Singles", "Morning singles", at(-4, 8), 90, "Achimota Golf Club courts",
				"Achimota, Accra", 2, 8000, "u-esi", null, List.of("u-esi", "u-abena"));
		list.add(withResult(tennis, new Result(2, 1, List.of("u-esi"), List.of("u-abena"), Map.of(),
				List.of(new int[] { 6, 4 }, new int[] { 3, 6 }, new int[] { 7, 5 }), "u-esi", at(-4, 10), List.of())));
		list.forEach(this::insertGame);

		// The venue owners' own blocks, so their dashboards start with a real schedule.
		block("b-blk-1", "v-osu", "p-osu-a", at(0, 17), at(0, 18), "Tuesday regulars — pay cash", at(-5, 9));
		block("b-blk-2", "v-osu", "p-osu-b", at(1, 6), at(1, 8), "Turf maintenance", at(-5, 9));
		block("b-blk-3", "v-osu", "p-osu-7", at(2, 19), at(2, 21), "Corporate booking (invoice)", at(-4, 9));
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

	/* ------------------------------------------------------------------ a league in progress */

	private record Team(String id, String name, String captain, List<String> players, String tint) {
	}

	private static final List<Team> TEAMS = List.of(
			new Team("t-ballers", "Osu Ballers", "u-kojo", List.of("u-kojo", "u-kwame", "u-kofi"), "#7cf0c8"),
			new Team("t-rovers", "Tema Rovers", "u-yaw", List.of("u-yaw", "u-nii"), "#a9c4f2"),
			new Team("t-hoopers", "Legon Hoopers", "u-abena", List.of("u-abena", "u-ama"), "#f2d4a9"),
			new Team("t-labone", "Labone United", "u-esi", List.of("u-esi", "u-akos"), "#d9b8e8"));

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
			jdbc.sql("""
					INSERT INTO teams (id, competition_id, name, captain_id, tint, join_token, created_at)
					VALUES (:id, :league, :name, :captain, :tint, :token, :created)
					""")
				.param("id", id(t.id())).param("league", id(league)).param("name", t.name()).param("captain", id(t.captain()))
				.param("tint", t.tint()).param("token", t.id().replace("t-", "") + "-squad").param("created", utc(created))
				.update();
			for (int i = 0; i < t.players().size(); i++) {
				jdbc.sql("INSERT INTO team_players (team_id, competition_id, user_id, added_at) VALUES (:team, :league, :user, :added)")
					.param("team", id(t.id())).param("league", id(league)).param("user", id(t.players().get(i)))
					.param("added", utc(created.plusSeconds(i)))
					.update();
			}
		}
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
		note("u-adwoa", "booking", "Kwame booked Pitch A", "Midweek 5s · Osu Astro Turf", "/venues/%s/manage".formatted(id("v-osu")), "u-kwame",
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
