package com.playchale.api.users.internal.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.auth.internal.service.AuthService;
import com.playchale.api.competitions.internal.domain.CompetitionDetails;
import com.playchale.api.competitions.internal.service.CompetitionService;
import com.playchale.api.games.internal.domain.GameDetails;
import com.playchale.api.games.internal.service.GameService;
import com.playchale.api.notifications.internal.service.NotificationService;
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

/** Deleting an account against a real Postgres, across every module that holds something about the player. */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, AccountDeletionTest.Clocks.class })
class AccountDeletionTest {

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
	UserProfileService profiles;

	@Autowired
	UserDirectory users;

	@Autowired
	AuthService auth;

	@Autowired
	GameService games;

	@Autowired
	VenueService venues;

	@Autowired
	CompetitionService competitions;

	@Autowired
	NotificationService notifications;

	@Autowired
	TestClock clock;

	@Autowired
	JdbcClient jdbc;

	UUID host;

	@BeforeEach
	void setUp() {
		jdbc.sql("""
				TRUNCATE users, sign_in_codes, sessions, venues, pitches, bookings, games, game_participants, notifications,
				         game_results, payments, movements, competitions, teams, team_players, join_requests CASCADE
				""").update();
		clock.set(NOW);
		host = users.registerOrFind("+233244555123", "GH").id();
	}

	private UUID game(long cost) {
		return games.create(new GameDetails("football", "5-a-side", "Saturday 5s", NOW.plus(Duration.ofDays(1)), 60, "unlisted", null, null,
				"Legon Park", null, 10, cost, "public", null), host).id();
	}

	@Test
	void aDeletedPlayerIsAnonymisedSignedOutAndGivesUpUnpaidSpots() {
		auth.requestCode("024 455 5124", null, "203.0.113.7");
		var signedIn = auth.signIn("024 455 5124", null, "123456");
		var kojo = signedIn.user().id();
		profiles.update(kojo, new ProfileChanges("Kojo Mensah", "kojo", "Osu", List.of("football"), null, "0201234567", "kojo@example.com"));

		var unpaid = game(25_000);
		var paid = game(25_000);
		games.join(unpaid, kojo);
		games.join(paid, kojo);
		games.markPaidCash(paid, kojo.toString(), host);
		games.invite(game(0), List.of(kojo), host);
		assertThat(notifications.list(kojo)).isNotEmpty();

		profiles.deleteAccount(kojo);

		var gone = users.find(kojo).orElseThrow();
		assertThat(gone.name()).isEqualTo("Deleted player");
		assertThat(gone.handle()).isEmpty();
		assertThat(gone.phone()).isEmpty();
		assertThat(gone.email()).isNull();
		assertThat(gone.payoutPhone()).isNull();
		assertThat(users.findByHandle("kojo")).isEmpty();
		assertThat(auth.userIdFor(signedIn.token())).as("signed out everywhere").isEmpty();
		assertThat(notifications.list(kojo)).isEmpty();
		assertThat(games.get(unpaid, host).players()).as("the unpaid spot is free again").hasSize(1);
		assertThat(games.get(paid, host).players()).as("the paid one stays paid for")
			.extracting(p -> p.name()).containsExactly("", "Deleted player").hasSize(2);

		// The number can start a fresh account.
		auth.requestCode("024 455 5124", null, "203.0.113.7");
		assertThat(auth.signIn("024 455 5124", null, "123456").user().id()).isNotEqualTo(kojo);
	}

	@Test
	void someoneOthersDependOnHasToSortThatOutFirst() {
		game(0);
		assertThatThrownBy(() -> profiles.deleteAccount(host))
			.hasMessage("You’re hosting a game that hasn’t happened yet. Call it off first, then delete your account.");

		var owner = users.registerOrFind("+233244100200", "GH").id();
		venues.create(owner, new VenueDetails("Osu Astro Turf", "Osu, Accra", null, null, null,
				List.of(new PitchDetails(null, "Pitch A", "football", "5-a-side", "turf", 25_000)),
				Collections.nCopies(7, new DayHours("06:00", "23:00")), List.of()));
		assertThatThrownBy(() -> profiles.deleteAccount(owner)).hasMessageStartingWith("You run a venue on PlayChale.");

		var organiser = users.registerOrFind("+233244555120", "GH").id();
		competitions.create(new CompetitionDetails("Office League", "football", "5-a-side", "unlisted", null, "Legon Park", null,
				NOW.plus(Duration.ofDays(7)), 60), organiser);
		assertThatThrownBy(() -> profiles.deleteAccount(organiser)).hasMessageStartingWith("You organise a league that’s still going.");
	}

}
