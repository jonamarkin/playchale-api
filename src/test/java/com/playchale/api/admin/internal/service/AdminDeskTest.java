package com.playchale.api.admin.internal.service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.auth.internal.service.GuestHosts;
import com.playchale.api.events.internal.service.EventService;
import com.playchale.api.games.internal.domain.GameDetails;
import com.playchale.api.games.internal.service.GameService;
import com.playchale.api.games.internal.service.GameTalk;
import com.playchale.api.shared.TestClock;
import com.playchale.api.usage.internal.service.UsageCounts;
import com.playchale.api.users.api.Terms;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.venues.internal.domain.DayHours;
import com.playchale.api.venues.internal.domain.PitchDetails;
import com.playchale.api.venues.internal.domain.VenueDetails;
import com.playchale.api.venues.internal.service.VenueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The admin desk, and mostly the gate in front of it: an admin account can read every phone number
 * in the country, so who may open one is the whole security question.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, AdminDeskTest.Clocks.class })
class AdminDeskTest {

	@TestConfiguration
	static class Clocks {

		@Bean
		@Primary
		TestClock testClock() {
			return new TestClock();
		}

	}

	private static final Instant NOW = Instant.parse("2026-09-25T08:00:00Z");

	@Autowired
	AdminDesk desk;

	@Autowired
	Staff staff;

	@Autowired
	GameService games;

	@Autowired
	GameTalk talk;

	@Autowired
	UserDirectory users;

	@Autowired
	UsageCounts usage;

	@Autowired
	TestClock clock;

	@Autowired
	EventService events;

	@Autowired
	GuestHosts guestHosts;

	@Autowired
	VenueService venues;

	@Autowired
	JdbcClient jdbc;

	UUID owner;

	UUID support;

	UUID player;

	@BeforeEach
	void setUp() {
		jdbc.sql("""
				TRUNCATE users, sign_in_codes, sessions, games, game_participants, game_messages, notifications, game_results,
				audit_events, platform_staff, usage_counts, rate_limits, organisations, events, host_keys, venues, pitches, bookings CASCADE
				""").update();
		clock.set(NOW);
		owner = user("+233244555123", "Ama Owner");
		support = user("+233244555124", "Sam Support");
		player = user("+233244555125", "Kojo Player");
		// The first row is an INSERT by hand on the server; nothing in the app can make one.
		jdbc.sql("INSERT INTO platform_staff (user_id, role) VALUES (:id, 'owner')").param("id", owner).update();
	}

	private UUID user(String phone, String name) {
		var id = users.registerOrFind(phone, "GH").id();
		jdbc.sql("UPDATE users SET name = :name WHERE id = :id").param("name", name).param("id", id).update();
		return id;
	}

	@Test
	void someoneWhoDoesNotWorkHereIsToldNothingExists() {
		// Not "forbidden": an admin area should not confirm itself to someone trying the door.
		assertThatThrownBy(() -> desk.find(player, "", 10)).hasMessage("Not found.");
		assertThatThrownBy(() -> desk.history(player, owner, 10)).hasMessage("Not found.");
		assertThatThrownBy(() -> desk.health(player, 30)).hasMessage("Not found.");
		assertThatThrownBy(() -> desk.export(player, owner)).hasMessage("Not found.");
		assertThatThrownBy(() -> desk.messages(player, 10)).hasMessage("Not found.");
	}

	@Test
	void onlyAnOwnerDecidesWhoWorksHere() {
		staff.add(owner, support, Staff.SUPPORT, "the first support hire");
		assertThat(staff.roleOf(support)).contains(Staff.SUPPORT);

		// Support can do the job, but cannot hand out the keys.
		assertThat(desk.find(support, "", 10)).isNotEmpty();
		assertThatThrownBy(() -> staff.add(support, player, Staff.SUPPORT, null))
			.hasMessage("Only an owner can change who works here.");
		assertThatThrownBy(() -> staff.remove(support, owner)).hasMessage("Only an owner can change who works here.");
	}

	@Test
	void anOwnerCannotLockEveryoneOutByRemovingThemselves() {
		assertThatThrownBy(() -> staff.remove(owner, owner)).hasMessage("You can’t remove yourself. Ask another owner.");
	}

	@Test
	void aClosedAccountIsNotStaffWhateverTheTableSays() {
		staff.add(owner, support, Staff.SUPPORT, null);
		jdbc.sql("UPDATE users SET deleted_at = now() WHERE id = :id").param("id", support).update();
		assertThat(staff.isStaff(support)).isFalse();
		assertThatThrownBy(() -> desk.find(support, "", 10)).hasMessage("Not found.");
	}

	@Test
	void peopleCanBeFoundByAnythingTheyAreKnownBy() {
		assertThat(desk.find(owner, "Kojo", 10)).singleElement().satisfies(p -> assertThat(p.name()).isEqualTo("Kojo Player"));
		// The way a number is written in Ghana, and the way it is written on the wire, both find them.
		assertThat(desk.find(owner, "0244555125", 10)).hasSize(1);
		assertThat(desk.find(owner, "024 455 5125", 10)).hasSize(1);
		assertThat(desk.find(owner, "+233244555125", 10)).hasSize(1);
		assertThat(desk.find(owner, "1", 10)).as("a single digit is not a phone search").isEmpty();
		assertThat(desk.find(owner, "", 10)).as("a blank search shows the newest, not nothing").hasSize(3);
	}

	@Test
	void lookingSomeoneUpIsItselfRecorded() {
		desk.history(owner, player, 20);
		var looked = jdbc.sql("SELECT count(*) FROM audit_events WHERE event_type = 'admin.viewed-person' AND actor_id = :id")
			.param("id", owner).query(Long.class).single();
		assertThat(looked).as("who looked at whom, with their name on it").isOne();
	}

	@Test
	void whatSomeoneSaidIsPartOfWhatHappenedToThem() {
		var game = games.create(new GameDetails("football", "5-a-side", "Saturday 5s", NOW.plus(Duration.ofHours(4)), 60, "unlisted", null,
				null, "Legon Park", "Legon", null, 10, 0, null, "public", null), player);
		talk.say(game.id(), "cheap jerseys, call me", player);
		assertThat(desk.history(owner, player, 20)).extracting(AdminDesk.Entry::what).contains("Said in Saturday 5s");
	}

	@Test
	void aMessageNobodyInTheGameWillRemoveCanBeTakenDown() {
		var game = games.create(new GameDetails("football", "5-a-side", "Saturday 5s", NOW.plus(Duration.ofHours(4)), 60, "unlisted", null,
				null, "Legon Park", "Legon", null, 10, 0, null, "public", null), player);
		var said = talk.say(game.id(), "Something out of order", player).get(0);

		assertThat(desk.messages(owner, 10)).singleElement().satisfies(m -> assertThat(m.body()).isEqualTo("Something out of order"));
		desk.removeMessage(owner, said.id(), "abuse");
		assertThat(desk.messages(owner, 10)).isEmpty();

		var why = jdbc.sql("SELECT details ->> 'why' FROM audit_events WHERE event_type = 'admin.removed-message'")
			.query(String.class).single();
		assertThat(why).isEqualTo("abuse");
	}

	@Test
	void theHealthNumbersCountWhatHappened() {
		var game = games.create(new GameDetails("football", "5-a-side", "Saturday 5s", NOW.plus(Duration.ofHours(4)), 60, "unlisted", null, null,
				"Legon Park", "Legon", null, 10, 0, null, "public", null), player);
		games.join(game.id(), support);
		games.leave(game.id(), support);
		var health = desk.health(owner, 30);
		assertThat(health.signups()).isEqualTo(3);
		assertThat(health.gamesCreated()).isOne();
		assertThat(health.gamesPlayed()).isZero();
		// The host's own spot, and Sam's join — which still happened, though he later left.
		assertThat(health.joins()).isEqualTo(2);
		assertThat(health.onboarded()).as("nobody has set up a profile yet").isZero();
		assertThat(health.eventsCreated()).isZero();
		assertThat(health.eventResults()).isZero();

		jdbc.sql("UPDATE users SET onboarded = true WHERE id = :id").param("id", player).update();
		assertThat(desk.health(owner, 30).onboarded()).isOne();
	}

	@Test
	void visitsArePagesAndFilmsCountedWithoutKnowingWho() {
		var browser = "Mozilla/5.0 (Linux; Android 14) Chrome/130.0 Mobile Safari/537.36";
		// A visit from WhatsApp lands on the games-day page and opens Discover; another comes straight in.
		usage.count("page", "/games-day", "https://web.whatsapp.com/", browser, "10.0.0.1");
		usage.count("page", "/games", "", browser, "10.0.0.1");
		usage.count("page", "/games-day", "direct", browser, "10.0.0.2");
		usage.count("film-start", "gamesday", null, browser, "10.0.0.2");
		usage.count("film-end", "gamesday", null, browser, "10.0.0.2");
		// Not counted: a search engine, a headless browser, an address rather than a route, an unknown kind.
		usage.count("page", "/games-day", "", "Mozilla/5.0 (compatible; Googlebot/2.1)", "10.0.0.3");
		usage.count("page", "/games-day", "", "Mozilla/5.0 HeadlessChrome/130.0", "10.0.0.3");
		usage.count("page", "/games/7f3e?phone=0244555123", "", browser, "10.0.0.3");
		usage.count("click", "/games-day", "", browser, "10.0.0.3");

		var visits = desk.visits(owner, 30);
		assertThat(visits.opens()).isEqualTo(3);
		assertThat(visits.arrivals()).isEqualTo(2);
		assertThat(visits.pages()).first().isEqualTo(new AdminDesk.Page("/games-day", 2, 2));
		assertThat(visits.sources()).extracting(AdminDesk.Count::name).containsExactlyInAnyOrder("whatsapp.com", "direct");
		assertThat(visits.films()).containsExactly(new AdminDesk.Film("gamesday", 1, 1));
		assertThat(visits.days()).singleElement().isEqualTo(new AdminDesk.Day("2026-09-25", 3));

		// Nothing in it says who: the table has no column that could.
		var columns = jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_name = 'usage_counts'").query(String.class).list();
		assertThat(columns).containsExactlyInAnyOrder("day", "kind", "name", "source", "n");

		assertThatThrownBy(() -> desk.visits(player, 30)).hasMessage("Not found.");
	}

	@Test
	void oneConnectionCantMakeAPageLookPopular() {
		var browser = "Mozilla/5.0 (Linux; Android 14) Chrome/130.0 Mobile Safari/537.36";
		for (int i = 0; i < UsageCounts.PER_CONNECTION_PER_HOUR + 5; i++) {
			usage.count("page", "/corporate-games", "", browser, "10.0.0.9");
		}
		assertThat(desk.visits(owner, 30).opens()).isEqualTo(UsageCounts.PER_CONNECTION_PER_HOUR);
	}

	@Test
	void anExportHoldsEverythingAboutOnePersonAndNobodyElse() {
		var game = games.create(new GameDetails("football", "5-a-side", "Saturday 5s", NOW.plus(Duration.ofHours(4)), 60, "unlisted", null,
				null, "Legon Park", "Legon", null, 10, 0, null, "public", null), player);
		talk.say(game.id(), "On my way", player);

		var export = desk.export(owner, player);
		assertThat(export).containsKeys("user", "games", "spots", "messages", "payments", "movements", "notifications");
		assertThat((List<?>) export.get("messages")).hasSize(1);
		assertThat((List<?>) desk.export(owner, support).get("messages")).as("and only theirs").isEmpty();
	}

	@Test
	void theChartHasEveryPeriodQuietOnesAsZerosAndLeavesGuestHostsOut() {
		guestHosts.start("Esi Owusu", "024 455 5130", "GH", Terms.CURRENT, "10.0.0.1", null);
		clock.set(NOW.minus(Duration.ofDays(3)));
		games.create(new GameDetails("football", "5-a-side", "Tuesday 5s", NOW.plus(Duration.ofHours(4)), 60, "unlisted", null, null,
				"Legon Park", "Legon", null, 10, 0, null, "public", null), player);
		clock.set(NOW);

		var week = desk.trends(owner, 7);
		assertThat(week.unit()).isEqualTo("day");
		assertThat(week.buckets()).extracting(AdminDesk.Bucket::start)
			.containsExactly("2026-09-19", "2026-09-20", "2026-09-21", "2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25");
		var today = week.buckets().getLast();
		assertThat(today.signups()).as("the three players, not the guest host").isEqualTo(3);
		assertThat(week.buckets().get(3).gamesCreated()).as("made on the 22nd").isOne();
		assertThat(week.buckets().get(3).activePeople()).as("the host took the first spot").isOne();
		assertThat(week.buckets().get(4).gamesCreated()).isZero();
		assertThat(desk.health(owner, 30).signups()).as("the totals agree").isEqualTo(3);

		var season = desk.trends(owner, 90);
		assertThat(season.unit()).isEqualTo("week");
		assertThat(season.buckets()).allSatisfy(b -> assertThat(LocalDate.parse(b.start()).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY));
		assertThat(season.buckets().stream().mapToLong(AdminDesk.Bucket::gamesCreated).sum()).isOne();

		var year = desk.trends(owner, 365);
		assertThat(year.unit()).isEqualTo("month");
		assertThat(year.buckets()).hasSize(13).allSatisfy(b -> assertThat(b.start()).endsWith("-01"));

		assertThatThrownBy(() -> desk.trends(player, 30)).hasMessage("Not found.");
	}

	@Test
	void theFunnelGoesFromAVisitToDoingSomethingAndCountsThoseWithoutAnAccount() {
		var browser = "Mozilla/5.0 (Linux; Android 14) Chrome/130.0 Mobile Safari/537.36";
		usage.count("page", "/games-day", "https://web.whatsapp.com/", browser, "10.0.0.1");
		usage.count("page", "/events/new", "", browser, "10.0.0.1");
		usage.count("page", "/games/new", "direct", browser, "10.0.0.2");
		games.create(new GameDetails("football", "5-a-side", "Saturday 5s", NOW.plus(Duration.ofHours(4)), 60, "unlisted", null, null,
				"Legon Park", "Legon", null, 10, 0, null, "private", null), player);

		// Two guest hosts; one signs up with the number they gave.
		var kept = guestHosts.start("Esi Owusu", "024 455 5131", "GH", Terms.CURRENT, "10.0.0.3", null).user().id();
		guestHosts.start("Kofi Mensah", "024 455 5132", "GH", Terms.CURRENT, "10.0.0.4", null);
		users.upgradeGuest(kept, "phone", "+233244555131", null);

		var funnel = desk.funnel(owner, 30);
		assertThat(funnel.visits()).isEqualTo(2);
		assertThat(funnel.openedForm()).isEqualTo(2);
		assertThat(funnel.signups()).as("the three players and Esi, now an account; not Kofi").isEqualTo(4);
		assertThat(funnel.didSomething()).as("Kojo hosts; Esi hosted nothing yet").isOne();
		assertThat(funnel.guests().hostsStarted()).isEqualTo(2);
		assertThat(funnel.guests().hostsKept()).isOne();

		assertThatThrownBy(() -> desk.funnel(player, 30)).hasMessage("Not found.");
	}

	@Test
	void eventsAreListedWithWhetherTheyreOnTodayAndHowFarTheirResultsHaveGot() {
		var today = LocalDate.ofInstant(NOW, ZoneOffset.UTC);
		var live = hillview("Hillview Games Day", today);
		hillview("Harvest Games", today.plusDays(14));
		var been = hillview("Easter Games", today.minusDays(30));
		events.addPeople(live, List.of(new EventService.PersonInput("Ama Boateng", null)), player);
		jdbc.sql("""
				INSERT INTO event_people (id, event_id, display_name, source, guest_phone, created_at, updated_at)
				VALUES (:id, :event, 'Kobby Guest', 'guest', '+233244555140', :now, :now)
				""").param("id", UUID.randomUUID()).param("event", live).param("now", NOW.atOffset(ZoneOffset.UTC)).update();
		var draughts = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO event_games (id, event_id, discipline, name, entry_kind, format, scoring, position, created_at, updated_at)
				VALUES (:id, :event, 'draughts', 'Draughts', 'single', 'knockout', 'outcome', 0, :now, :now)
				""").param("id", draughts).param("event", live).param("now", NOW.atOffset(ZoneOffset.UTC)).update();
		for (int slot = 0; slot < 2; slot++) {
			jdbc.sql("INSERT INTO event_matches (id, game_id, round, slot, recorded_at) VALUES (:id, :game, 1, :slot, :at)")
				.param("id", UUID.randomUUID()).param("game", draughts).param("slot", slot)
				.param("at", slot == 0 ? NOW.atOffset(ZoneOffset.UTC) : null).update();
		}

		var upcoming = desk.events(owner, "upcoming", null, 50);
		assertThat(upcoming).extracting(AdminDesk.EventRow::name).containsExactly("Hillview Games Day", "Harvest Games");
		var row = upcoming.getFirst();
		assertThat(row.live()).isTrue();
		assertThat(row.organisation()).isEqualTo("Hillview Chapel");
		assertThat(row.people()).isEqualTo(2);
		assertThat(row.guests()).isOne();
		assertThat(row.resultsIn()).isOne();
		assertThat(row.resultsTotal()).isEqualTo(2);
		assertThat(upcoming.get(1).live()).isFalse();
		assertThat(desk.events(owner, "past", null, 50)).extracting(AdminDesk.EventRow::id).containsExactly(been);
		assertThat(desk.events(owner, "upcoming", "harvest", 50)).extracting(AdminDesk.EventRow::name).containsExactly("Harvest Games");
		assertThat(desk.events(owner, "upcoming", "hillview chapel", 50)).hasSize(2);

		// Its organisers, to call on the day: seeing their numbers is recorded.
		var detail = desk.event(support(), live);
		assertThat(detail.organisers()).singleElement().satisfies(o -> {
			assertThat(o.name()).isEqualTo("Kojo Player");
			assertThat(o.phone()).isEqualTo("+233244555125");
			assertThat(o.role()).isEqualTo("owner");
		});
		assertThat(detail.games()).singleElement().satisfies(g -> {
			assertThat(g.name()).isEqualTo("Draughts");
			assertThat(g.resultsIn()).isOne();
			assertThat(g.resultsTotal()).isEqualTo(2);
		});
		assertThat(recorded("admin.viewed-event")).isOne();
		// A first event made with its workspace is in the event stream too.
		assertThat(recorded("event.created")).isEqualTo(3);

		assertThatThrownBy(() -> desk.events(player, "upcoming", null, 50)).hasMessage("Not found.");
		assertThatThrownBy(() -> desk.event(player, live)).hasMessage("Not found.");
	}

	@Test
	void gamesAreFoundByTitlePlaceOrHostAndAGuestHostIsMarked() {
		var saturday = games.create(new GameDetails("football", "5-a-side", "Saturday 5s", NOW.plus(Duration.ofHours(4)), 60, "unlisted", null,
				null, "Legon Park", "Legon", null, 10, 0, null, "public", null), player);
		games.join(saturday.id(), support);
		clock.set(NOW.plusSeconds(60));
		var guest = guestHosts.start("Esi Owusu", "024 455 5133", "GH", Terms.CURRENT, "10.0.0.5", null).user().id();
		games.create(new GameDetails("football", "5-a-side", "Esi’s kickabout", NOW.plus(Duration.ofDays(1)), 60, "unlisted", null, null,
				"Osu Park", "Osu", null, 10, 0, null, "private", null), guest);

		var newest = desk.games(owner, "new", null, 50);
		assertThat(newest).extracting(AdminDesk.GameRow::title).containsExactly("Esi’s kickabout", "Saturday 5s");
		assertThat(newest.getFirst().hostGuest()).isTrue();
		assertThat(newest.getFirst().visibility()).isEqualTo("private");
		assertThat(newest.get(1).players()).isEqualTo(2);
		assertThat(desk.games(owner, "upcoming", null, 50)).extracting(AdminDesk.GameRow::title).containsExactly("Saturday 5s", "Esi’s kickabout");
		assertThat(desk.games(owner, "new", "osu", 50)).extracting(AdminDesk.GameRow::title).containsExactly("Esi’s kickabout");
		assertThat(desk.games(owner, "new", "kojo", 50)).extracting(AdminDesk.GameRow::title).containsExactly("Saturday 5s");
		assertThat(desk.games(owner, "cancelled", null, 50)).isEmpty();
		assertThatThrownBy(() -> desk.games(player, "new", null, 50)).hasMessage("Not found.");
	}

	@Test
	void aFakeGameIsTakenDownAndEveryoneInItIsToldWhy() {
		var fake = games.create(new GameDetails("football", "5-a-side", "Free iPhones here", NOW.plus(Duration.ofHours(4)), 60, "unlisted", null,
				null, "Legon Park", "Legon", null, 10, 0, null, "public", null), player);
		games.join(fake.id(), support);

		assertThatThrownBy(() -> desk.takeDown(owner, fake.id(), " ")).hasMessageContaining("Say why");
		assertThatThrownBy(() -> desk.takeDown(player, fake.id(), "Spam")).hasMessage("Not found.");
		desk.takeDown(owner, fake.id(), "Not a real game: it advertises phones.");

		var row = desk.games(owner, "cancelled", null, 50).getFirst();
		assertThat(row.status()).isEqualTo("cancelled");
		assertThat(row.cancelReason()).isEqualTo("Not a real game: it advertises phones.");
		for (var person : List.of(player, support)) {
			assertThat(jdbc.sql("SELECT body FROM notifications WHERE user_id = :id AND title = 'Free iPhones here was taken down'")
				.param("id", person).query(String.class).single()).contains("PlayChale took this game down").contains("advertises phones");
		}
		assertThat(recorded("admin.took-down-game")).isOne();
		assertThat(recorded("game.taken-down")).isOne();
		assertThatThrownBy(() -> desk.takeDown(owner, fake.id(), "Again")).hasMessage("This game is already called off.");

		var played = games.create(new GameDetails("football", "5-a-side", "Last week", NOW.plus(Duration.ofHours(5)), 60, "unlisted", null, null,
				"Legon Park", "Legon", null, 10, 0, null, "public", null), player);
		jdbc.sql("UPDATE games SET status = 'completed' WHERE id = :id").param("id", played.id()).update();
		assertThatThrownBy(() -> desk.takeDown(owner, played.id(), "Spam")).hasMessageContaining("has a result");
	}

	@Test
	void aNewVenueWaitsOnTheDeskUntilStaffApproveItOrSayWhatToChange() {
		var hours = Collections.nCopies(7, new DayHours("06:00", "23:00"));
		var osu = venues.create(player, new VenueDetails("Halfway Line Turf", "Osu, Accra", "Floodlit turf by the lighthouse", "Behind the lighthouse",
				null, "+233244555125", List.of(new PitchDetails(null, "Pitch A", "football", "5-a-side", "turf", 25_000)), hours, List.of("floodlights")));
		var fake = venues.create(support, new VenueDetails("Free Jerseys Arena", "Accra", null, null, null, null,
				List.of(new PitchDetails(null, "Pitch", "football", "5-a-side", "turf", 100)), hours, List.of()));

		assertThat(desk.venuesWaiting(owner)).isEqualTo(2);
		var waiting = desk.venues(owner, "waiting", null, 50);
		assertThat(waiting).extracting(AdminDesk.VenueRow::name).containsExactly("Halfway Line Turf", "Free Jerseys Arena");
		var row = waiting.getFirst();
		assertThat(row.ownerName()).isEqualTo("Kojo Player");
		assertThat(row.phone()).isEqualTo("+233244555125");
		assertThat(row.amenities()).containsExactly("floodlights");
		assertThat(row.pitches()).containsExactly(new AdminDesk.VenuePitch("Pitch A", "football", "5-a-side", 25_000));
		assertThat(venues.search("", null)).as("hidden from players while it waits").isEmpty();

		desk.approveVenue(owner, osu.id());
		assertThatThrownBy(() -> desk.declineVenue(owner, fake.id(), " ")).hasMessageContaining("Say what needs changing");
		desk.declineVenue(owner, fake.id(), "We couldn’t find this venue. Send us its address.");

		assertThat(desk.venuesWaiting(owner)).isZero();
		assertThat(desk.venues(owner, "live", null, 50)).extracting(AdminDesk.VenueRow::name).containsExactly("Halfway Line Turf");
		assertThat(desk.venues(owner, "declined", null, 50)).singleElement()
			.satisfies(v -> assertThat(v.reviewNote()).isEqualTo("We couldn’t find this venue. Send us its address."));
		assertThat(venues.search("", null)).extracting(v -> v.name()).containsExactly("Halfway Line Turf");
		assertThat(recorded("admin.approved-venue")).isOne();
		assertThat(recorded("admin.declined-venue")).isOne();
		assertThat(jdbc.sql("SELECT title FROM notifications WHERE user_id = :id").param("id", player).query(String.class).list())
			.containsExactly("Halfway Line Turf is live");

		// A live venue can be taken off too: its owner hears it's off.
		desk.declineVenue(owner, osu.id(), "Players say it closed down.");
		assertThat(venues.search("", null)).isEmpty();
		assertThat(jdbc.sql("SELECT title FROM notifications WHERE user_id = :id").param("id", player).query(String.class).list())
			.containsExactlyInAnyOrder("Halfway Line Turf is live", "Halfway Line Turf is off PlayChale for now");

		assertThatThrownBy(() -> desk.venues(player, "waiting", null, 50)).hasMessage("Not found.");
		assertThatThrownBy(() -> desk.approveVenue(player, fake.id())).hasMessage("Not found.");
	}

	/** An event run by Kojo's workspace "Hillview Chapel", made the way a first one is: with its workspace. */
	private UUID hillview(String name, LocalDate day) {
		var existing = jdbc.sql("SELECT id FROM organisations WHERE name = 'Hillview Chapel'").query(UUID.class).optional();
		var input = new EventService.EventInput(name, day, day, "Africa/Accra", "GH", null, List.of(new EventService.GroupInput("Joy Fellowship", null)),
				null, null, null, null);
		var made = existing.isPresent() ? events.create(existing.get(), input, player)
				: events.createWithWorkspace(new EventService.WorkspaceInput("Hillview Chapel", null), input, player);
		return made.event().id();
	}

	/** Sam, made support staff by the owner. */
	private UUID support() {
		jdbc.sql("INSERT INTO platform_staff (user_id, role) VALUES (:id, 'support') ON CONFLICT DO NOTHING").param("id", support).update();
		return support;
	}

	private int recorded(String type) {
		return jdbc.sql("SELECT count(*) FROM audit_events WHERE event_type = :type").param("type", type).query(Integer.class).single();
	}

}
