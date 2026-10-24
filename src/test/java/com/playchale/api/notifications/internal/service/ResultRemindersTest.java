package com.playchale.api.notifications.internal.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.games.internal.domain.GameDetails;
import com.playchale.api.games.internal.domain.ResultInput;
import com.playchale.api.games.internal.service.GameService;
import com.playchale.api.games.internal.service.ResultService;
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

/** The host asked for the score: half an hour after the game, again the next morning, then left alone. */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, ResultRemindersTest.Clocks.class })
class ResultRemindersTest {

	@TestConfiguration
	static class Clocks {

		@Bean
		@Primary
		TestClock testClock() {
			return new TestClock();
		}

	}

	/** Saturday 17 October 2026, 10 am in Accra: an hour-long game. */
	private static final Instant KICKOFF = Instant.parse("2026-10-17T10:00:00Z");

	private static final Instant FULL_TIME = KICKOFF.plus(Duration.ofHours(1));

	@Autowired
	ResultReminders reminders;

	@Autowired
	GameService games;

	@Autowired
	ResultService results;

	@Autowired
	UserDirectory users;

	@Autowired
	TestClock clock;

	@Autowired
	JdbcClient jdbc;

	UUID kwame;

	UUID kojo;

	@BeforeEach
	void setUp() {
		jdbc.sql("TRUNCATE users, sessions, games, game_participants, notifications, result_reminders, game_results, rate_limits CASCADE").update();
		clock.set(KICKOFF.minus(Duration.ofDays(1)));
		kwame = member("+233244555701", "Kwame Mensah");
		kojo = member("+233244555702", "Kojo Owusu");
	}

	private UUID member(String phone, String name) {
		var id = users.registerOrFind(phone, "GH").id();
		jdbc.sql("UPDATE users SET name = :name, onboarded = true WHERE id = :id").param("name", name).param("id", id).update();
		return id;
	}

	private UUID game(String title, Instant startsAt) {
		var id = games.create(new GameDetails("football", "5-a-side", title, startsAt, 60, "unlisted", null, null, "Legon Park", "Legon", null,
				10, 0, null, "public", null), kwame).id();
		games.join(id, kojo);
		return id;
	}

	private List<String> asked(UUID user) {
		return jdbc.sql("SELECT title FROM notifications WHERE user_id = :id AND kind = 'game-reminder' ORDER BY created_at").param("id", user)
			.query(String.class).list();
	}

	@Test
	void theHostIsAskedAfterTheGameAndAgainNextMorningThenLeftAlone() {
		var saturday = game("Saturday 5s", KICKOFF);

		// Not before half an hour has passed.
		clock.set(FULL_TIME.plus(Duration.ofMinutes(10)));
		assertThat(reminders.send()).isZero();

		clock.set(FULL_TIME.plus(Duration.ofMinutes(31)));
		assertThat(reminders.send()).isEqualTo(1);
		assertThat(asked(kwame)).containsExactly("How did it go? · Saturday 5s");
		// The players aren't asked: only the host can add it.
		assertThat(asked(kojo)).isEmpty();
		// Once, however often it runs; and not again the same evening.
		clock.set(FULL_TIME.plus(Duration.ofHours(8)));
		assertThat(reminders.send()).isZero();

		// Next morning, from nine.
		clock.set(Instant.parse("2026-10-18T08:30:00Z"));
		assertThat(reminders.send()).isZero();
		clock.set(Instant.parse("2026-10-18T09:15:00Z"));
		assertThat(reminders.send()).isEqualTo(1);
		assertThat(asked(kwame)).containsExactly("How did it go? · Saturday 5s", "Still no score for Saturday 5s");
		assertThat(reminders.asksFor(saturday)).isEqualTo(2);

		// Then no more, ever.
		clock.set(Instant.parse("2026-10-18T15:00:00Z"));
		assertThat(reminders.send()).isZero();
		clock.set(Instant.parse("2026-10-19T10:00:00Z"));
		assertThat(reminders.send()).isZero();
	}

	@Test
	void aGameWithItsScoreInOrCalledOffIsNotAsked() {
		var scored = game("Scored 5s", KICKOFF);
		var calledOff = game("Called off 5s", KICKOFF.plus(Duration.ofHours(2)));
		clock.set(FULL_TIME);
		results.record(scored, new ResultInput(2, 1, List.of(kwame.toString()), List.of(kojo.toString()), null, null, null), kwame);
		games.cancel(calledOff, "Rain", kwame);

		clock.set(FULL_TIME.plus(Duration.ofHours(4)));
		assertThat(reminders.send()).isZero();
		assertThat(asked(kwame)).isEmpty();
	}

	@Test
	void aGameLongPastIsLeftAlone() {
		var lastWeek = game("Last week's 5s", KICKOFF);
		jdbc.sql("UPDATE games SET starts_at = :at WHERE id = :id").param("at", KICKOFF.minus(Duration.ofDays(7)).atOffset(ZoneOffset.UTC))
			.param("id", lastWeek).update();
		clock.set(KICKOFF);
		assertThat(reminders.send()).isZero();
	}

}
