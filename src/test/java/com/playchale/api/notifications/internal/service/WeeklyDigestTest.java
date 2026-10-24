package com.playchale.api.notifications.internal.service;

import java.time.Duration;
import java.time.Instant;
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

/** The Monday round-up's "Around you": who was voted player of the match, how much was played, what's on near them. */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, WeeklyDigestTest.Clocks.class })
class WeeklyDigestTest {

	@TestConfiguration
	static class Clocks {

		@Bean
		@Primary
		TestClock testClock() {
			return new TestClock();
		}

	}

	/** Monday 19 October 2026, 7 am in Accra. */
	private static final Instant MONDAY = Instant.parse("2026-10-19T07:00:00Z");

	@Autowired
	WeeklyDigest digest;

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

	UUID esi;

	UUID chidi;

	@BeforeEach
	void setUp() {
		jdbc.sql("TRUNCATE users, sessions, games, game_participants, game_results, notifications, email_preferences, mailouts, mailout_recipients, rate_limits CASCADE")
			.update();
		clock.set(MONDAY.minus(Duration.ofDays(20)));
		kwame = member("+233244555901", "Kwame Mensah", "GH", "{football}", "{\"Osu, Accra\"}");
		kojo = member("+233244555902", "Kojo Owusu", "GH", "{football}", "{\"Osu, Accra\"}");
		esi = member("+233244555903", "Esi Appiah", "GH", "{football}", "{\"Osu, Accra\"}");
		chidi = member("+447700900123", "Chidi Okafor", "GB", "{football}", "{London}");
		for (var id : List.of(esi, chidi)) {
			agreed(id);
		}

		// Last Tuesday: Kwame and Kojo played; Kojo voted Kwame player of the match, and the vote closed.
		var tuesday = MONDAY.minus(Duration.ofDays(6));
		clock.set(tuesday.minus(Duration.ofDays(1)));
		var game = games.create(new GameDetails("football", "5-a-side", "Tuesday 5s", tuesday, 60, "unlisted", null, null,
				"Halfway Line Turf", "Osu, Accra", null, 10, 0, null, "public", null), kwame).id();
		games.join(game, kojo);
		clock.set(tuesday.plus(Duration.ofHours(2)));
		results.record(game, new ResultInput(3, 1, List.of(kwame.toString()), List.of(kojo.toString()), null, null, null), kwame);
		results.vote(game, kwame.toString(), kojo);
		// And a game this coming Thursday with room.
		clock.set(MONDAY);
		games.create(new GameDetails("football", "5-a-side", "Thursday 5s", MONDAY.plus(Duration.ofDays(3)), 60, "unlisted", null, null,
				"Halfway Line Turf", "Osu, Accra", null, 10, 0, null, "public", null), kwame);
	}

	private UUID member(String phone, String name, String country, String sports, String areas) {
		var id = users.registerOrFind(phone, country).id();
		jdbc.sql("""
				UPDATE users SET name = :name, onboarded = true, sports = CAST(:sports AS text[]), areas = CAST(:areas AS text[]),
				       area = (CAST(:areas AS text[]))[1], sign_in_email = :email
				WHERE id = :id
				""").param("name", name).param("sports", sports).param("areas", areas).param("email", name.split(" ")[0].toLowerCase() + "@playchale.com")
			.param("id", id).update();
		return id;
	}

	private void agreed(UUID user) {
		jdbc.sql("""
				INSERT INTO email_preferences (user_id, muted, marketing, digest, weekend, token, updated_at)
				VALUES (:id, '{}', true, 'weekly', true, gen_random_uuid(), now())
				""").param("id", user).update();
	}

	@Test
	void anEmptyWeekOfYourOwnStillTellsYouWhatHappenedAroundYou() {
		var mailout = digest.send();
		assertThat(mailout).isNotNull();
		// Esi didn't play, but Ghana did, and there's a game near her; Chidi in London hears nothing.
		var recipients = jdbc.sql("SELECT email FROM mailout_recipients WHERE mailout_id = :id").param("id", mailout).query(String.class).list();
		assertThat(recipients).containsExactly("esi@playchale.com");
	}

	@Test
	void theLinesAroundSomeoneAreTheirCountrysWeekAndWhatIsNearThem() {
		var now = clock.instant();
		var around = new WeeklyDigest.Around(digestWeek().playersOfTheMatchBetween(now.minus(Duration.ofDays(7)), now),
				digestWeek().playedSince(now.minus(Duration.ofDays(7))), openGames().between(now, now.plus(Duration.ofDays(7))), users);
		assertThat(around.linesFor(users.find(esi).orElseThrow())).isEqualTo("""
				1 game played by 2 people in Ghana this week.
				Kwame · player of the match in Tuesday 5s

				Near you this week:
				Thursday 5s · Thu 7:00 am · Halfway Line Turf · 9 spots left""");
		assertThat(around.linesFor(users.find(chidi).orElseThrow())).isEmpty();
	}

	@Autowired
	com.playchale.api.games.api.WeekInGames week;

	@Autowired
	com.playchale.api.games.api.OpenGames open;

	private com.playchale.api.games.api.WeekInGames digestWeek() {
		return week;
	}

	private com.playchale.api.games.api.OpenGames openGames() {
		return open;
	}

}
