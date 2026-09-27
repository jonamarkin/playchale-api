package com.playchale.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.playchale.api.competitions.internal.domain.CompetitionDetails;
import com.playchale.api.competitions.internal.service.CompetitionService;
import com.playchale.api.games.api.GameResponse;
import com.playchale.api.games.internal.domain.GameDetails;
import com.playchale.api.games.internal.service.GameFilters;
import com.playchale.api.games.internal.service.GameService;
import com.playchale.api.notifications.internal.service.NotificationResponse;
import com.playchale.api.notifications.internal.service.NotificationService;
import com.playchale.api.payments.internal.service.PaymentService;
import com.playchale.api.shared.TestClock;
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

/** Where something is played sets its money and its clock: games, venues and leagues outside Ghana. */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CountriesTest.Clocks.class })
class CountriesTest {

	@TestConfiguration
	static class Clocks {

		@Bean
		@Primary
		TestClock testClock() {
			return new TestClock();
		}

	}

	/** Friday 25 September, 8am UTC (9am in London, which is on summer time). */
	private static final Instant NOW = Instant.parse("2026-09-25T08:00:00Z");

	/** Saturday 26 September, 6pm in London. */
	private static final Instant LONDON_SIX_PM = Instant.parse("2026-09-26T17:00:00Z");

	@Autowired
	GameService games;

	@Autowired
	VenueService venues;

	@Autowired
	CompetitionService competitions;

	@Autowired
	PaymentService payments;

	@Autowired
	NotificationService notifications;

	@Autowired
	UserDirectory users;

	@Autowired
	TestClock clock;

	@Autowired
	JdbcClient jdbc;

	UUID chidi;

	UUID tunde;

	@BeforeEach
	void setUp() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, venues, pitches, bookings, games, game_participants, notifications, competitions, payments CASCADE")
			.update();
		clock.set(NOW);
		chidi = user("+447400123456", "GB", "Chidi Okafor");
		tunde = user("+447400654321", "GB", "Tunde Bello");
	}

	private UUID user(String phone, String country, String name) {
		var id = users.registerOrFind(phone, country).id();
		jdbc.sql("UPDATE users SET name = :name WHERE id = :id").param("name", name).param("id", id).update();
		return id;
	}

	private static GameDetails hackney(long totalCost) {
		return new GameDetails("football", "5-a-side", "Hackney 5s", LONDON_SIX_PM, 60, "unlisted", null, null, "Hackney Marshes", "Hackney",
				null, 10, totalCost, null, "public", null);
	}

	private List<String> bodiesFor(UUID user) {
		return notifications.list(user).stream().map(NotificationResponse::body).toList();
	}

	@Test
	void aGameAnywhereIsInItsCountrysMoneyAndLocalTime() {
		assertThatThrownBy(() -> games.create(hackney(5_000), "Mars/Olympus", null, null, chidi)).hasMessage("Pick a time zone from the list.");

		var game = games.create(hackney(5_000), "Europe/London", null, null, chidi);
		assertThat(game.country()).as("the host's country").isEqualTo("GB");
		assertThat(game.currency()).isEqualTo("GBP");
		assertThat(game.timezone()).isEqualTo("Europe/London");
		assertThat(game.share()).as("£50 split 10 ways").isEqualTo(500);

		games.invite(game.id(), List.of(tunde), chidi);
		assertThat(bodiesFor(tunde)).singleElement().asString()
			.as("London time and pounds").isEqualTo("Tomorrow · 6:00 pm · Hackney Marshes · £ 5 each. Say if you’re in.");

		var again = games.repeat(game.id(), chidi);
		assertThat(again.timezone()).as("a week later, same place").isEqualTo("Europe/London");
		assertThat(again.currency()).isEqualTo("GBP");

		games.join(game.id(), tunde);
		assertThatThrownBy(() -> payments.start(game.id(), "card", null, tunde))
			.hasMessage("Paying in the app isn’t available in this country yet. Pay the host directly, and they’ll mark you as paid.");
	}

	@Test
	void aVenueIsWhereItIsAndItsGamesToo() {
		var hours = Collections.nCopies(7, new DayHours("06:00", "23:00"));
		var pitches = List.of(new PitchDetails(null, "Pitch A", "football", "5-a-side", "turf", 2_000_000));
		var details = new VenueDetails("Lekki Arena", "Lekki, Lagos", null, null, null, "0803 123 4567", pitches, hours, List.of());
		assertThatThrownBy(() -> venues.create(chidi, details, "XX", "Africa/Lagos")).hasMessage("Pick the country your venue is in.");

		var lekki = venues.create(chidi, details, "NG", "Africa/Lagos");
		assertThat(lekki.country()).isEqualTo("NG");
		assertThat(lekki.currency()).isEqualTo("NGN");
		assertThat(lekki.phone()).as("a Nigerian number, though the owner lives in the UK").isEqualTo("+2348031234567");

		var game = games.create(new GameDetails("football", "5-a-side", null, NOW.plus(Duration.ofDays(1)), 60, "listed", lekki.id(), null, null,
				null, null, 10, 0, null, "public", null), "Europe/London", null, null, chidi);
		assertThat(game.country()).as("the venue's, not the host's").isEqualTo("NG");
		assertThat(game.currency()).isEqualTo("NGN");
		assertThat(game.timezone()).isEqualTo("Africa/Lagos");
	}

	@Test
	void aLeagueAbroadPlaysItsFixturesInItsOwnTime() {
		var league = competitions.create(new CompetitionDetails("Sunday League", "football", "5-a-side", "unlisted", null, "Hackney Marshes",
				"Hackney", null, LONDON_SIX_PM, 60, null, "Europe/London"), chidi);
		assertThat(league.country()).isEqualTo("GB");
		assertThat(league.currency()).isEqualTo("GBP");
		assertThat(league.timezone()).isEqualTo("Europe/London");
		for (var team : List.of("Reds", "Blues", "Greens")) {
			competitions.addTeam(league.id(), null, team, null, null, chidi);
		}
		var drawn = competitions.generateFixtures(league.id(), chidi);
		assertThat(drawn.fixtures()).allSatisfy(f -> {
			assertThat(f.country()).isEqualTo("GB");
			assertThat(f.timezone()).isEqualTo("Europe/London");
		});
		// Three rounds, a week apart in London time: the last is 10 October at 6pm there.
		assertThat(drawn.fixtures().stream().map(f -> f.startsAt()).sorted().toList().getLast())
			.isEqualTo(LONDON_SIX_PM.plus(Duration.ofDays(14)));
	}

	@Test
	void discoverShowsYourCountryOrEverywhere() {
		var kofi = user("+233244555126", "GH", "Kofi Asare");
		var london = games.create(hackney(0), "Europe/London", null, null, chidi);
		var accra = games.create(new GameDetails("football", "5-a-side", "Legon 5s", NOW.plus(Duration.ofDays(1)), 60, "unlisted", null, null,
				"Legon Park", "Legon", null, 10, 0, null, "public", null), "Africa/Accra", null, null, kofi);

		assertThat(games.list(new GameFilters(null, null, null, "GB", "Europe/London"), chidi)).extracting(GameResponse::id).containsExactly(london.id());
		assertThat(games.list(new GameFilters(null, null, null, "gh", null), chidi)).extracting(GameResponse::id).containsExactly(accra.id());
		assertThat(games.list(new GameFilters(null, null, null, null, null), chidi)).as("everywhere").extracting(GameResponse::id)
			.containsExactlyInAnyOrder(london.id(), accra.id());
		assertThat(games.list(new GameFilters(null, null, "tomorrow", "GB", "Europe/London"), chidi)).as("tomorrow on a London clock")
			.extracting(GameResponse::id).containsExactly(london.id());

		var hours = Collections.nCopies(7, new DayHours("06:00", "23:00"));
		var pitches = List.of(new PitchDetails(null, "Pitch A", "football", "5-a-side", "turf", 2_000_000));
		venues.create(chidi, new VenueDetails("Lekki Arena", "Lekki, Lagos", null, null, null, null, pitches, hours, List.of()), "NG", "Africa/Lagos");
		assertThat(venues.search("", "NG")).extracting(v -> v.name()).containsExactly("Lekki Arena");
		assertThat(venues.search("", "GB")).isEmpty();
		assertThat(venues.search("lekki", null)).as("anywhere").hasSize(1);
	}

}
