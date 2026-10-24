package com.playchale.api.notifications.internal.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.games.internal.domain.GameDetails;
import com.playchale.api.games.internal.service.GameService;
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

/** "Still playing?": a fortnight away, something near them, once, and not again for two months. */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, ComeBackNudgeTest.Clocks.class })
class ComeBackNudgeTest {

	@TestConfiguration
	static class Clocks {

		@Bean
		@Primary
		TestClock testClock() {
			return new TestClock();
		}

	}

	/** Tuesday 20 October 2026, 10 am in Accra. */
	private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");

	@Autowired
	ComeBackNudge nudge;

	@Autowired
	GameService games;

	@Autowired
	UserDirectory users;

	@Autowired
	TestClock clock;

	@Autowired
	JdbcClient jdbc;

	UUID kojo;

	UUID esi;

	UUID ama;

	UUID nii;

	@BeforeEach
	void setUp() {
		jdbc.sql("TRUNCATE users, sessions, games, game_participants, notifications, email_preferences, mailouts, mailout_recipients, come_back_nudges, rate_limits CASCADE")
			.update();
		// Everyone joined a month ago.
		clock.set(NOW.minus(Duration.ofDays(30)));
		kojo = member("+233244555801", "Kojo Mensah", "{football}", "{\"Osu, Accra\"}");
		esi = member("+233244555802", "Esi Appiah", "{football}", "{\"Osu, Accra\"}");
		ama = member("+233244555803", "Ama Serwaa", "{tennis}", "{Kumasi}");
		nii = member("+233244555804", "Nii Lamptey", "{football}", "{\"Osu, Accra\"}");
		// Kojo was here yesterday; Esi and Ama three weeks ago; Nii hosts the game, so he's not away either.
		seen(kojo, NOW.minus(Duration.ofDays(1)));
		seen(esi, NOW.minus(Duration.ofDays(21)));
		seen(ama, NOW.minus(Duration.ofDays(21)));
		seen(nii, NOW.minus(Duration.ofHours(2)));
		clock.set(NOW);
		games.create(new GameDetails("football", "5-a-side", "Thursday 5s", NOW.plus(Duration.ofDays(2)), 60, "unlisted", null, null,
				"Halfway Line Turf", "Osu, Accra", null, 10, 0, null, "public", null), nii);
	}

	private UUID member(String phone, String name, String sports, String areas) {
		var id = users.registerOrFind(phone, "GH").id();
		jdbc.sql("""
				UPDATE users SET name = :name, onboarded = true, sports = CAST(:sports AS text[]), areas = CAST(:areas AS text[]),
				       area = (CAST(:areas AS text[]))[1], created_at = :created
				WHERE id = :id
				""").param("name", name).param("sports", sports).param("areas", areas).param("created", clock.instant().atOffset(ZoneOffset.UTC))
			.param("id", id).update();
		return id;
	}

	private void seen(UUID user, Instant at) {
		jdbc.sql("INSERT INTO sessions (token_hash, user_id, created_at, expires_at, last_seen_at) VALUES (:token, :user, :at, :expires, :at)")
			.param("token", "t-" + user + "-" + at.toEpochMilli()).param("user", user).param("at", at.atOffset(ZoneOffset.UTC))
			.param("expires", at.plus(Duration.ofDays(90)).atOffset(ZoneOffset.UTC)).update();
	}

	private List<String> nudges(UUID user) {
		return jdbc.sql("SELECT title || ' | ' || body FROM notifications WHERE user_id = :id AND kind = 'nearby'").param("id", user)
			.query(String.class).list();
	}

	@Test
	void someoneAwayAFortnightWithAGameNearThemIsAskedOnce() {
		assertThat(nudge.send()).isEqualTo(1);
		// Esi: away, and 5s in Osu is her thing.
		assertThat(nudges(esi)).singleElement().isEqualTo("Still playing? A game near you this week | Thursday 5s · Thu 10:00 am · Halfway Line Turf · 9 spots left");
		// Kojo was here yesterday; Ama plays tennis in Kumasi, and nothing's near her; Nii is hosting.
		assertThat(nudges(kojo)).isEmpty();
		assertThat(nudges(ama)).isEmpty();
		assertThat(nudges(nii)).isEmpty();

		// Not again tomorrow, nor next month (Kojo and Nii keep coming; Esi stays away).
		clock.set(NOW.plus(Duration.ofDays(1)));
		assertThat(nudge.send()).isZero();
		clock.set(NOW.plus(Duration.ofDays(30)));
		seen(kojo, clock.instant());
		seen(nii, clock.instant());
		games.create(new GameDetails("football", "5-a-side", "Saturday 5s", NOW.plus(Duration.ofDays(32)), 60, "unlisted", null, null,
				"Halfway Line Turf", "Osu, Accra", null, 10, 0, null, "public", null), nii);
		assertThat(nudge.send()).isZero();
		// Two months on, still away: once more.
		clock.set(NOW.plus(Duration.ofDays(61)));
		seen(kojo, clock.instant());
		seen(nii, clock.instant());
		games.create(new GameDetails("football", "5-a-side", "Sunday 5s", NOW.plus(Duration.ofDays(63)), 60, "unlisted", null, null,
				"Halfway Line Turf", "Osu, Accra", null, 10, 0, null, "public", null), nii);
		assertThat(nudge.send()).isEqualTo(1);
		assertThat(nudges(esi)).hasSize(2);
	}

	@Test
	void someoneWhoJustJoinedIsNotNudgedAndEmailGoesOnlyToThoseWhoAgreed() {
		// Esi agreed to hear from PlayChale; a newcomer who hasn't opened the app since signing up last week is left alone.
		jdbc.sql("UPDATE users SET sign_in_email = 'esi@playchale.com' WHERE id = :id").param("id", esi).update();
		jdbc.sql("""
				INSERT INTO email_preferences (user_id, muted, marketing, digest, weekend, token, updated_at)
				VALUES (:id, '{}', true, 'weekly', true, gen_random_uuid(), now())
				""").param("id", esi).update();
		clock.set(NOW.minus(Duration.ofDays(6)));
		var newcomer = member("+233244555805", "Yaw Boateng", "{football}", "{\"Osu, Accra\"}");
		clock.set(NOW);

		assertThat(nudge.send()).isEqualTo(1);
		assertThat(nudges(newcomer)).isEmpty();
		assertThat(jdbc.sql("SELECT r.email FROM mailout_recipients r JOIN mailouts m ON m.id = r.mailout_id WHERE m.kind = 'weekend'")
			.query(String.class).list()).containsExactly("esi@playchale.com");
	}

}
