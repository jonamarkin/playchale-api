package com.playchale.api.games.internal.service;

import com.playchale.api.games.api.GameResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.games.internal.domain.GameDetails;
import com.playchale.api.games.internal.domain.ResultInput;
import com.playchale.api.games.internal.domain.ResultInput.Scorer;
import com.playchale.api.games.internal.domain.SetScore;
import com.playchale.api.notifications.internal.service.NotificationResponse;
import com.playchale.api.notifications.internal.service.NotificationService;
import com.playchale.api.profiles.internal.service.PlayerProfileService;
import com.playchale.api.profiles.internal.service.SportRecord;
import com.playchale.api.shared.TestClock;
import com.playchale.api.users.api.UserDirectory;
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

/** Results against a real Postgres, through to the notifications they send and the profiles they change. */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, ResultServiceTest.Clocks.class })
class ResultServiceTest {

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
	GameService games;

	@Autowired
	ResultService results;

	@Autowired
	PlayerProfileService profiles;

	@Autowired
	NotificationService notifications;

	@Autowired
	UserDirectory users;

	@Autowired
	TestClock clock;

	@Autowired
	JdbcClient jdbc;

	UUID kwame;

	UUID kojo;

	UUID ama;

	@BeforeEach
	void setUp() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, games, game_participants, notifications, game_results CASCADE").update();
		clock.set(NOW);
		kwame = user("+233244555123", "Kwame Mensah");
		kojo = user("+233244555124", "Kojo Owusu");
		ama = user("+233244555125", "Ama Serwaa");
	}

	private UUID user(String phone, String name) {
		var id = users.registerOrFind(phone, "GH").id();
		jdbc.sql("UPDATE users SET name = :name, sports = '{football}' WHERE id = :id").param("name", name).param("id", id).update();
		return id;
	}

	/** A game Kwame hosts that Kojo and Ama join, kicking off {@code hoursFromNow} after NOW. */
	private GameResponse played(String sport, String format, int hoursFromNow) {
		var game = games.create(new GameDetails(sport, format, null, NOW.plus(Duration.ofHours(hoursFromNow)), 60, "unlisted", null, null,
				"Legon Park", null, 10, 0, "public", null), kwame);
		games.join(game.id(), kojo);
		games.join(game.id(), ama);
		return game;
	}

	private List<NotificationResponse> notificationsFor(UUID user) {
		return notifications.list(user);
	}

	@Test
	void theHostRecordsTheScoreAndPlayersHearHowTheyDid() {
		var game = played("football", "5-a-side", 1);
		assertThatThrownBy(() -> results.record(game.id(), new ResultInput(3, 1, List.of(kwame.toString()), List.of(ama.toString()), null,
				null, null), kwame)).hasMessage("You can record the result once the game has started.");

		clock.set(NOW.plus(Duration.ofHours(3)));
		assertThatThrownBy(() -> results.record(game.id(), new ResultInput(1, 0, List.of(kwame.toString()), List.of(ama.toString()), null,
				null, null), kojo)).hasMessage("Only the host can record the result.");

		var done = results.record(game.id(), new ResultInput(3, 1, List.of(kwame.toString(), kojo.toString()), List.of(ama.toString()),
				List.of(new Scorer(kojo.toString(), 2, 1, null)), null, null), kwame);
		assertThat(done.status()).isEqualTo("completed");
		assertThat(done.result().sides().home()).containsExactly(kwame.toString(), kojo.toString());
		assertThat(done.result().scorers()).singleElement().satisfies(s -> assertThat(s.goals()).isEqualTo(2));

		assertThat(notificationsFor(kojo).getFirst().title()).isEqualTo("Result: 5-a-side football");
		assertThat(notificationsFor(kojo).getFirst().body()).isEqualTo("You won 3–1. Your stats are updated.");
		assertThat(notificationsFor(ama).getFirst().body()).isEqualTo("You lost 1–3. Your stats are updated.");

		results.confirm(game.id(), kojo);
		results.dispute(game.id(), "It was 3-2", ama);
		assertThat(notificationsFor(kwame).getFirst().title()).isEqualTo("Ama says the result isn’t right");

		clock.advance(Duration.ofMinutes(5));
		var corrected = results.record(game.id(), new ResultInput(3, 2, List.of(kwame.toString(), kojo.toString()), List.of(ama.toString()),
				List.of(new Scorer(kojo.toString(), 2, 1, null)), null, null), kwame);
		assertThat(corrected.result().confirmedBy()).isEmpty();
		assertThat(corrected.result().disputes()).isEmpty();
		assertThat(notificationsFor(ama).getFirst().title()).isEqualTo("Result corrected: 5-a-side football");
	}

	@Test
	void profilesAddUpEveryResultPerSportWithTheLatestForm() {
		var first = played("football", "5-a-side", 1);
		var second = played("football", "5-a-side", 2);
		var volleyball = played("volleyball", "6v6", 3);
		clock.set(NOW.plus(Duration.ofHours(5)));

		results.record(first.id(), new ResultInput(3, 1, List.of(kwame.toString(), kojo.toString()), List.of(ama.toString()),
				List.of(new Scorer(kojo.toString(), 2, 1, null)), null, null), kwame);
		results.record(second.id(), new ResultInput(2, 2, List.of(kwame.toString()), List.of(kojo.toString()),
				List.of(new Scorer(kojo.toString(), 1, null, null)), null, List.of(ama.toString())), kwame);
		results.record(volleyball.id(), new ResultInput(0, 0, List.of(kwame.toString(), ama.toString()), List.of(kojo.toString()), null,
				List.of(new SetScore(25, 20), new SetScore(18, 25), new SetScore(15, 12)), null), kwame);

		var profile = profiles.get(kojo, Optional.empty());
		assertThat(profile.stats().games()).isEqualTo(3);
		assertThat(profile.stats().wins()).isEqualTo(1);
		assertThat(profile.stats().goals()).isEqualTo(3);
		assertThat(profile.form()).containsExactly("W", "D", "L");
		assertThat(profile.bySport()).extracting(SportRecord::sport).containsExactly("football", "volleyball");
		assertThat(profile.bySport().get(1).stats().setsWon()).as("the away side won one set").isEqualTo(1);

		var history = profiles.history(kojo);
		assertThat(history).hasSize(3);
		assertThat(history.getFirst().sport()).as("newest first").isEqualTo("volleyball");
		assertThat(history.getFirst().sets()).as("from Kojo's side").first().satisfies(s -> {
			assertThat(s.home()).isEqualTo(20);
			assertThat(s.away()).isEqualTo(25);
		});
		assertThat(profiles.history(ama)).as("absent from the draw").hasSize(2);
	}

}
