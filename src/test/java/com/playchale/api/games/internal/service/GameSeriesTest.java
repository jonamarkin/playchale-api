package com.playchale.api.games.internal.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.games.api.GameResponse;
import com.playchale.api.games.internal.domain.GameDetails;
import com.playchale.api.games.internal.domain.SeriesChange;
import com.playchale.api.notifications.internal.service.NotificationResponse;
import com.playchale.api.notifications.internal.service.NotificationService;
import com.playchale.api.shared.TestClock;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.users.internal.service.UserProfileService;
import com.playchale.api.venues.internal.domain.DayHours;
import com.playchale.api.venues.internal.domain.PitchDetails;
import com.playchale.api.venues.internal.domain.VenueDetails;
import com.playchale.api.venues.internal.service.BookingService;
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
 * Repeating games against a real Postgres: when the next game opens, who's asked to it, what stops
 * it, and that a date only ever opens once.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, GameSeriesTest.Clocks.class })
class GameSeriesTest {

	@TestConfiguration
	static class Clocks {

		@Bean
		@Primary
		TestClock testClock() {
			return new TestClock();
		}

	}

	/** Friday morning in Accra (UTC all year). Kick-off is Saturday 26 September, 10:00, for an hour. */
	private static final Instant NOW = Instant.parse("2026-09-25T08:00:00Z");

	private static final Instant KICKOFF = Instant.parse("2026-09-26T10:00:00Z");

	private static final Duration WEEK = Duration.ofDays(7);

	@Autowired
	GameSeriesService series;

	@Autowired
	SeriesOpener opener;

	@Autowired
	GameService games;

	@Autowired
	NotificationService notifications;

	@Autowired
	VenueService venues;

	@Autowired
	BookingService bookings;

	@Autowired
	UserProfileService profiles;

	@Autowired
	UserDirectory users;

	@Autowired
	TestClock clock;

	@Autowired
	JdbcClient jdbc;

	UUID kwame;

	UUID kojo;

	UUID ama;

	UUID adwoa;

	@BeforeEach
	void setUp() {
		jdbc.sql("""
				TRUNCATE users, sign_in_codes, sessions, venues, pitches, bookings, games, game_participants, game_invites, notifications,
				game_series, game_series_optouts, audit_events CASCADE
				""").update();
		clock.set(NOW);
		kwame = user("+233244555123", "Kwame Mensah");
		kojo = user("+233244555124", "Kojo Owusu");
		ama = user("+233244555125", "Ama Serwaa");
		adwoa = user("+233244100200", "Adwoa Boateng");
	}

	private UUID user(String phone, String name) {
		var id = users.registerOrFind(phone, "GH").id();
		jdbc.sql("UPDATE users SET name = :name WHERE id = :id").param("name", name).param("id", id).update();
		return id;
	}

	private static GameDetails saturday5s() {
		return new GameDetails("football", "5-a-side", "Saturday 5s", KICKOFF, 60, "unlisted", null, null, "Legon Park", "Legon", null, 10, 0,
				null, "public", null);
	}

	private GameResponse weekly() {
		return series.start(saturday5s(), "Africa/Accra", "weekly", null, kwame);
	}

	/** The series' games, in kick-off order. */
	private List<Instant> gamesOf(UUID seriesId) {
		return jdbc.sql("SELECT starts_at FROM games WHERE series_id = :id ORDER BY starts_at").param("id", seriesId).query(Instant.class).list();
	}

	private UUID latestOf(UUID seriesId) {
		return jdbc.sql("SELECT id FROM games WHERE series_id = :id ORDER BY starts_at DESC LIMIT 1").param("id", seriesId).query(UUID.class)
			.single();
	}

	/** Time moves to just after a game's end, and the opener runs as it would on its own. */
	private void afterEndOf(Instant kickoff) {
		clock.set(kickoff.plus(Duration.ofMinutes(61)));
		opener.openDue();
	}

	private List<String> titlesFor(UUID user) {
		return notifications.list(user).stream().map(NotificationResponse::title).toList();
	}

	@Test
	void theNextGameOpensWhenTheLastOneEndsAndNotBefore() {
		var first = weekly();
		var id = first.series().id();
		assertThat(first.series().frequency()).isEqualTo("weekly");
		assertThat(first.series().kickOff()).isEqualTo("10:00");
		assertThat(first.series().nextStartsAt()).isEqualTo(KICKOFF.plus(WEEK));

		opener.openDue();
		clock.set(KICKOFF.plus(Duration.ofMinutes(30)));
		opener.openDue();
		assertThat(gamesOf(id)).as("nothing until this one is over").containsExactly(KICKOFF);

		afterEndOf(KICKOFF);
		opener.openDue();
		assertThat(gamesOf(id)).as("then exactly one more, a week on").containsExactly(KICKOFF, KICKOFF.plus(WEEK));
		assertThat(titlesFor(kwame)).anyMatch(t -> t.startsWith("Saturday 5s is set for"));
	}

	@Test
	void theLastGamesPlayersAreAskedButNotGuestsLeaversOrThoseWhoSaidNo() {
		var first = weekly();
		var id = first.series().id();
		games.join(first.id(), kojo);
		games.join(first.id(), ama);
		games.join(first.id(), adwoa);
		games.leave(first.id(), adwoa);
		games.addGuest(first.id(), "Kofi from work", null, kwame);
		series.optOut(id, true, ama);

		afterEndOf(KICKOFF);
		var next = games.get(latestOf(id), kwame);
		assertThat(next.invites()).extracting(GameResponse.InviteResponse::userId).containsExactly(kojo);
		assertThat(next.players()).as("asked, not put in").hasSize(1);
		assertThat(titlesFor(kojo)).contains("Saturday 5s is on again");
		assertThat(titlesFor(ama)).doesNotContain("Saturday 5s is on again");

		// Ama can still join, and asking to be invited again works for the one after.
		games.join(next.id(), ama);
		series.optOut(id, false, ama);
		afterEndOf(next.startsAt());
		assertThat(games.get(latestOf(id), kwame).invites()).extracting(GameResponse.InviteResponse::userId).containsExactly(ama);
	}

	@Test
	void aTakenPitchSkipsThatDateTellsTheHostAndCarriesOn() {
		var osu = venues.create(adwoa, new VenueDetails("Halfway Line Turf", "Osu, Accra", null, null, null, null,
				List.of(new PitchDetails(null, "Pitch A", "football", "5-a-side", "turf", 25_000)),
				Collections.nCopies(7, new DayHours("06:00", "23:00")), List.of()));
		var pitch = osu.pitches().getFirst().id();
		var first = series.start(new GameDetails("football", "5-a-side", "Saturday 5s", KICKOFF, 60, "listed", osu.id(), pitch, null, null, null,
				10, 25_000, null, "public", null), null, "weekly", null, kwame);
		var id = first.series().id();
		bookings.block(adwoa, osu.id(), pitch, KICKOFF.plus(WEEK), KICKOFF.plus(WEEK).plus(Duration.ofHours(1)), "Tournament");

		// Seen before anything is created: no game, no booking attempt, and the host told why.
		assertThat(series.openIfDue(id)).isEmpty();
		clock.set(KICKOFF.plus(Duration.ofMinutes(61)));
		assertThat(series.openIfDue(id)).isEmpty();
		assertThat(gamesOf(id)).as("not opened somewhere else, or at another time").containsExactly(KICKOFF);
		assertThat(notifications.list(kwame)).filteredOn(n -> n.title().startsWith("Saturday 5s skips")).singleElement()
			.satisfies(n -> assertThat(n.body()).isEqualTo("That time is already blocked. It carries on with the date after."));

		afterEndOf(KICKOFF.plus(WEEK));
		assertThat(gamesOf(id)).as("the week after is free").containsExactly(KICKOFF, KICKOFF.plus(WEEK.multipliedBy(2)));
		var booked = jdbc.sql("SELECT count(*) FROM bookings WHERE game_id = :id").param("id", latestOf(id)).query(Long.class).single();
		assertThat(booked).as("and its pitch is booked for it").isOne();
	}

	@Test
	void twoPlayedGamesWithNobodyComingPauseItAndACalledOffOneDoesNotCount() {
		var first = weekly();
		var id = first.series().id();

		afterEndOf(KICKOFF);
		var second = latestOf(id);
		games.cancel(second, "Rain", kwame);
		afterEndOf(KICKOFF.plus(WEEK));
		assertThat(gamesOf(id)).as("one empty game and one rained off: carries on").hasSize(3);

		afterEndOf(KICKOFF.plus(WEEK.multipliedBy(2)));
		assertThat(gamesOf(id)).as("the second empty one: paused, not a fourth").hasSize(3);
		assertThat(series.mine(kwame).getFirst().status()).isEqualTo("paused");
		assertThat(titlesFor(kwame)).contains("Saturday 5s is paused");

		var restarted = series.restart(id, kwame);
		assertThat(restarted.status()).isEqualTo("active");
		assertThat(gamesOf(id)).as("back on: the next one opens straight away").hasSize(4);
	}

	@Test
	void stoppingItOpensNothingMoreAndLeavesTheGameToCome() {
		var first = weekly();
		var id = first.series().id();
		series.stop(id, kwame);
		assertThat(games.get(first.id(), kwame).status()).as("still on").isEqualTo("open");

		afterEndOf(KICKOFF);
		assertThat(gamesOf(id)).containsExactly(KICKOFF);
		assertThatThrownBy(() -> series.stop(id, kwame)).hasMessage("This game has already stopped repeating.");
	}

	@Test
	void aChangeAppliesFromTheNextGameNotTheOneAlreadyOpen() {
		var first = weekly();
		var id = first.series().id();
		var changed = series.change(id, new SeriesChange(null, 7, LocalTime.of(17, 0), "weekly", null, 90, 12, 0, null, "public", "Bring water"),
				kwame);
		// Sundays from the week after this Saturday's game, not the next morning.
		assertThat(changed.nextStartsAt()).isEqualTo(Instant.parse("2026-10-04T17:00:00Z"));
		assertThat(games.get(first.id(), kwame).capacity()).isEqualTo(10);

		afterEndOf(KICKOFF);
		var next = games.get(latestOf(id), kwame);
		assertThat(next.startsAt()).isEqualTo(Instant.parse("2026-10-04T17:00:00Z"));
		assertThat(next.capacity()).isEqualTo(12);
		assertThat(next.durationMinutes()).isEqualTo(90);
		assertThat(next.notes()).isEqualTo("Bring water");

		assertThatThrownBy(() -> series.change(id, new SeriesChange(null, 7, LocalTime.of(17, 0), "weekly", null, 90, 1, 0, null, "public", null),
				kwame)).as("checked as a game is, now").hasMessage("A game needs at least 2 spots.");
	}

	@Test
	void twoOpenersAtOnceOpenOneGame() throws Exception {
		var id = weekly().series().id();
		clock.set(KICKOFF.plus(Duration.ofMinutes(61)));
		var start = new CountDownLatch(1);
		Callable<Boolean> open = () -> {
			start.await();
			return series.openIfDue(id).isPresent();
		};
		try (var pool = Executors.newFixedThreadPool(2)) {
			var a = pool.submit(open);
			var b = pool.submit(open);
			start.countDown();
			assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(true, false);
		}
		assertThat(gamesOf(id)).hasSize(2);
	}

	@Test
	void aGameAlreadyOverRepeatsFromTheNextWeekStillToCome() {
		var game = games.create(saturday5s(), kwame);
		games.join(game.id(), kojo);
		clock.set(KICKOFF.plus(Duration.ofDays(9)));

		var next = series.startFrom(game.id(), "weekly", null, kwame);
		assertThat(next.startsAt()).as("a week on is gone; the Saturday after").isEqualTo(KICKOFF.plus(WEEK.multipliedBy(2)));
		assertThat(next.invites()).extracting(GameResponse.InviteResponse::userId).containsExactly(kojo);
		assertThat(games.get(game.id(), kwame).series()).isNotNull();
	}

	@Test
	void sameAgainNextWeekTappedLateStillLandsInTheFuture() {
		var game = games.create(saturday5s(), kwame);
		clock.set(KICKOFF.plus(Duration.ofDays(10)));
		assertThat(games.repeat(game.id(), kwame).startsAt()).isEqualTo(KICKOFF.plus(WEEK.multipliedBy(2)));

		clock.set(NOW);
		var repeating = weekly();
		assertThatThrownBy(() -> games.repeat(repeating.id(), kwame)).hasMessage("This game repeats already. The next one opens when this one ends.");
	}

	@Test
	void aMonthlyRuleThatIsntTheFirstGamesDayCreatesNothing() {
		// 26 September 2026 is the 4th (and last) Saturday, not the 1st.
		assertThatThrownBy(() -> series.start(saturday5s(), "Africa/Accra", "monthly", 1, kwame)).hasMessageStartingWith("26 September isn’t");
		assertThat(jdbc.sql("SELECT count(*) FROM games").query(Long.class).single()).isZero();
	}

	@Test
	void onlyTheHostRunsItAndTheHostCantOptOut() {
		var first = weekly();
		var id = first.series().id();
		var change = new SeriesChange(null, 6, LocalTime.of(10, 0), "weekly", null, 60, 10, 0, null, "public", null);
		assertThatThrownBy(() -> series.change(id, change, kojo)).hasMessage("Only the host can do that.");
		assertThatThrownBy(() -> series.stop(id, kojo)).hasMessage("Only the host can do that.");
		assertThatThrownBy(() -> series.startFrom(games.create(saturday5s(), kwame).id(), "weekly", null, kojo))
			.hasMessage("Only the host can do that.");
		assertThatThrownBy(() -> series.optOut(id, true, kwame)).hasMessage("You host this game, so there’s nobody to invite you.");
	}

	@Test
	void closingTheHostsAccountStopsIt() {
		var first = weekly();
		var id = first.series().id();
		games.cancel(first.id(), null, kwame);
		profiles.deleteAccount(kwame);

		var status = jdbc.sql("SELECT status FROM game_series WHERE id = :id").param("id", id).query(String.class).single();
		assertThat(status).isEqualTo("stopped");
		afterEndOf(KICKOFF);
		assertThat(gamesOf(id)).hasSize(1);
	}

}
