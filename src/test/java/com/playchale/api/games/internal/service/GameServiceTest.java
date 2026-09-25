package com.playchale.api.games.internal.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.games.internal.domain.GameDetails;
import com.playchale.api.notifications.internal.service.NotificationResponse;
import com.playchale.api.notifications.internal.service.NotificationService;
import com.playchale.api.shared.TestClock;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.users.api.UserDirectory;
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

/** Games against a real Postgres: rosters under concurrency, pitches, claim links, notifications. */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, GameServiceTest.Clocks.class })
class GameServiceTest {

	@TestConfiguration
	static class Clocks {

		@Bean
		@Primary
		TestClock testClock() {
			return new TestClock();
		}

	}

	/** Friday morning in Accra. Kick-off is Saturday 10:00. */
	private static final Instant NOW = Instant.parse("2026-09-25T08:00:00Z");

	private static final Instant KICKOFF = Instant.parse("2026-09-26T10:00:00Z");

	@Autowired
	GameService games;

	@Autowired
	NotificationService notifications;

	@Autowired
	VenueService venues;

	@Autowired
	BookingService bookings;

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
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, venues, pitches, bookings, games, game_participants, notifications CASCADE").update();
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

	private static GameDetails unlisted(int capacity, long totalCost) {
		return new GameDetails("football", "5-a-side", "Saturday 5s", KICKOFF, 60, "unlisted", null, null, "Legon Park", "Legon",
				capacity, totalCost, "public", null);
	}

	private List<String> titlesFor(UUID user) {
		return notifications.list(user).stream().map(NotificationResponse::title).toList();
	}

	@Test
	void twoPlayersRacingForTheLastSpotCantBothGetIt() throws Exception {
		var game = games.create(unlisted(2, 0), kwame);
		var start = new CountDownLatch(1);
		Callable<Boolean> kojoJoins = () -> tryJoin(game.id(), kojo, start);
		Callable<Boolean> amaJoins = () -> tryJoin(game.id(), ama, start);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var a = pool.submit(kojoJoins);
			var b = pool.submit(amaJoins);
			start.countDown();
			assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(true, false);
		}
		var after = games.get(game.id(), kwame);
		assertThat(after.players()).hasSize(2);
		assertThat(after.status()).isEqualTo("full");
		assertThat(titlesFor(kwame)).containsExactly("Saturday 5s is full");
	}

	private boolean tryJoin(UUID gameId, UUID player, CountDownLatch start) throws InterruptedException {
		start.await();
		try {
			games.join(gameId, player);
			return true;
		}
		catch (BusinessException e) {
			assertThat(e.getMessage()).isEqualTo("Sorry, this game just filled up.");
			return false;
		}
	}

	@Test
	void aGameOnAPartnerPitchBooksItAndCallingItOffReleasesIt() {
		var osu = venues.create(adwoa, new VenueDetails("Osu Astro Turf", "Osu, Accra", null, null, null,
				List.of(new PitchDetails(null, "Pitch A", "football", "5-a-side", "turf", 25_000)),
				Collections.nCopies(7, new DayHours("06:00", "23:00")), List.of()));
		var pitch = osu.pitches().getFirst().id();
		var details = new GameDetails("football", "5-a-side", null, KICKOFF, 60, "listed", osu.id(), pitch, null, null, 10, 25_000,
				"public", null);

		var game = games.create(details, kwame);
		assertThat(game.venue().pitchName()).isEqualTo("Pitch A");
		assertThat(game.venue().name()).isEqualTo("Osu Astro Turf");
		assertThat(game.share()).isEqualTo(2_500);
		assertThat(titlesFor(adwoa)).containsExactly("Kwame booked Pitch A");
		assertThat(bookings.schedule(adwoa, osu.id(), KICKOFF, KICKOFF.plusSeconds(3600)).getFirst().gameTitle()).isEqualTo("5-a-side football");

		assertThatThrownBy(() -> games.create(details, kojo)).hasMessage("Pitch A was just booked for that time. Pick another slot.");

		games.join(game.id(), kojo);
		games.cancel(game.id(), "Pitch flooded", kwame);
		assertThat(titlesFor(kojo)).contains("5-a-side football is off");
		assertThat(notifications.list(kojo).getFirst().body()).endsWith("“Pitch flooded”");
		assertThat(titlesFor(adwoa)).contains("Kwame released Pitch A");
		// The slot can be sold again.
		games.create(details, kojo);
	}

	@Test
	void aClaimLinkWorksOnceForTheRightNumberAndItsTokenStaysSecret() {
		var game = games.create(unlisted(10, 0), kwame);
		var added = games.addGuest(game.id(), "Kojo from work", "024 455 5124", kwame);
		var spotToken = added.game().participants().get(1).guest().token();
		assertThat(spotToken).as("the roster shows a spot ID, not the claim secret").isNotEqualTo(added.token());
		assertThat(games.get(game.id(), ama).participants().get(1).guest().phone()).as("only the host sees the number").isNull();

		assertThatThrownBy(() -> games.claimSpot(game.id(), spotToken, kojo))
			.hasMessage("That invite has already been used, or the host removed the spot.");
		assertThatThrownBy(() -> games.claimSpot(game.id(), added.token(), ama))
			.hasMessage("This invite was sent to a different number. Ask the host to add yours.");

		var claimed = games.claimSpot(game.id(), added.token(), kojo);
		assertThat(claimed.players()).extracting(GameResponse.PlayerResponse::name).contains("Kojo Owusu");
		assertThat(titlesFor(kwame)).containsExactly("Kojo claimed their spot in Saturday 5s");
		assertThatThrownBy(() -> games.claimSpot(game.id(), added.token(), ama))
			.hasMessage("That invite has already been used, or the host removed the spot.");
	}

	@Test
	void theHostRunsTheRosterAndPlayersHearAboutIt() {
		var game = games.create(unlisted(10, 25_000), kwame);
		games.join(game.id(), kojo);
		clock.advance(Duration.ofMinutes(1));
		games.join(game.id(), ama);
		assertThat(titlesFor(kwame)).containsExactly("Ama joined Saturday 5s", "Kojo joined Saturday 5s");

		assertThat(games.remind(game.id(), null, kwame)).isEqualTo(2);
		assertThat(titlesFor(kojo)).containsExactly("Pay your GH₵ 25 share");

		games.markPaidCash(game.id(), kojo.toString(), kwame);
		assertThat(games.remind(game.id(), null, kwame)).as("Kojo paid; only Ama owes").isEqualTo(1);

		games.removePlayer(game.id(), ama.toString(), kwame);
		assertThat(titlesFor(ama)).contains("You’re no longer in Saturday 5s");

		var guest = games.addGuest(game.id(), "Kofi", null, kwame).game();
		var guestKey = guest.players().getLast().id();
		assertThat(guestKey).startsWith("guest:");
		games.markPaidCash(game.id(), guestKey, kwame);
		assertThat(games.removePlayer(game.id(), guestKey, kwame).players()).hasSize(2);

		assertThatThrownBy(() -> games.remind(game.id(), null, kojo)).hasMessage("Only the host can do that.");
		assertThat(games.invite(game.id(), List.of(ama, kojo, kwame, UUID.randomUUID()), kwame)).as("only Ama isn't in").isEqualTo(1);
		assertThat(titlesFor(ama)).contains("Kwame invited you to Saturday 5s");

		var next = games.repeat(game.id(), kwame);
		assertThat(next.startsAt()).isEqualTo(KICKOFF.plus(Duration.ofDays(7)));
	}

	@Test
	void discoverShowsUpcomingGamesTheViewerMaySee() {
		var saturday = games.create(unlisted(10, 0), kwame);
		var privateGame = games.create(new GameDetails("basketball", "3x3", "Friends only", NOW.plus(Duration.ofHours(3)), 60, "unlisted",
				null, null, "Kojo's court", "Tema", 6, 0, "private", null), kojo);

		assertThat(ids(games.list(new GameFilters(null, null, null), ama))).containsExactly(saturday.id());
		assertThat(ids(games.list(new GameFilters(null, null, null), kojo))).containsExactly(privateGame.id(), saturday.id());
		assertThat(ids(games.list(new GameFilters(null, null, "today"), kojo))).containsExactly(privateGame.id());
		assertThat(ids(games.list(new GameFilters(null, null, "tomorrow"), ama))).containsExactly(saturday.id());
		assertThat(ids(games.list(new GameFilters(null, null, "weekend"), ama))).containsExactly(saturday.id());
		assertThat(ids(games.list(new GameFilters("legon", "football", null), null))).containsExactly(saturday.id());
		assertThat(ids(games.list(new GameFilters(null, "tennis", null), null))).isEmpty();
		assertThat(ids(games.mine(kojo))).containsExactly(privateGame.id());
	}

	private static List<UUID> ids(List<GameResponse> found) {
		return new ArrayList<>(found.stream().map(GameResponse::id).toList());
	}

}
