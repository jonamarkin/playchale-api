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

/**
 * Friday's "this weekend near you": the open games near each person this weekend, never "nothing's
 * on", a host with nothing on asked to put one up, once a weekend, and by email only to those who
 * agreed to hear from PlayChale.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, WeekendNudgeTest.Clocks.class })
class WeekendNudgeTest {

	@TestConfiguration
	static class Clocks {

		@Bean
		@Primary
		TestClock testClock() {
			return new TestClock();
		}

	}

	/** Friday 16 October 2026, 5 pm in Accra. */
	private static final Instant FRIDAY = Instant.parse("2026-10-16T17:00:00Z");

	private static final Instant SATURDAY_MORNING = Instant.parse("2026-10-17T09:00:00Z");

	@Autowired
	WeekendNudge nudge;

	@Autowired
	GameService games;

	@Autowired
	UserDirectory users;

	@Autowired
	TestClock clock;

	@Autowired
	JdbcClient jdbc;

	UUID kojo;

	UUID ama;

	UUID esi;

	UUID yaw;

	UUID nii;

	@BeforeEach
	void setUp() {
		jdbc.sql("""
				TRUNCATE users, sessions, games, game_participants, game_series, notifications, email_preferences, mailouts, mailout_recipients,
				weekend_nudges, rate_limits CASCADE
				""").update();
		clock.set(FRIDAY.minus(Duration.ofDays(20)));
		kojo = member("+233244555501", "Kojo Mensah", "{football}", "{\"Osu, Accra\"}");
		ama = member("+233244555502", "Ama Serwaa", "{basketball}", "{Tema}");
		esi = member("+233244555503", "Esi Appiah", "{}", "{}");
		yaw = member("+233244555504", "Yaw Boateng", "{tennis}", "{Kumasi}");
		nii = member("+233244555505", "Nii Lamptey", "{volleyball}", "{\"Labadi, Accra\"}");
		users.registerOrFind("+233244555506", "GH");

		// Yaw put a game on a fortnight ago; nothing this weekend.
		var old = game("Midweek tennis", "tennis", FRIDAY.minus(Duration.ofDays(10)), "Baseline Courts", "Kumasi", "public", 4, yaw);
		jdbc.sql("UPDATE games SET starts_at = :at WHERE id = :id").param("at", FRIDAY.minus(Duration.ofDays(14)).atOffset(ZoneOffset.UTC)).param("id", old)
			.update();

		clock.set(FRIDAY);
		game("Saturday 5s", "football", SATURDAY_MORNING, "Halfway Line Turf", "Osu, Accra", "public", 10, nii);
		game("Beach volley", "volleyball", SATURDAY_MORNING.plus(Duration.ofHours(5)), "Labadi Beach", "Osu, Accra", "public", 8, nii);
		game("Invite only hoops", "basketball", SATURDAY_MORNING, "Community Court", "Tema", "private", 10, nii);
		game("Next week's 5s", "football", FRIDAY.plus(Duration.ofDays(7)), "Halfway Line Turf", "Osu, Accra", "public", 10, nii);
	}

	@Test
	void eachPersonHearsAboutTheGamesNearThemAndNobodyHearsNothingIsOn() {
		assertThat(nudge.send()).isEqualTo(3);

		var forKojo = notice(kojo);
		assertThat(forKojo.title()).isEqualTo("2 games near you this weekend");
		assertThat(forKojo.body()).as("his sport first").startsWith("Saturday 5s · Sat 9:00 am · Halfway Line Turf · 9 spots left").endsWith("· and 1 more");
		assertThat(forKojo.link()).isEqualTo("/games?when=weekend");

		// Esi said neither sports nor places: every open game in Ghana this weekend.
		assertThat(notice(esi).title()).isEqualTo("2 games near you this weekend");
		// Ama plays basketball in Tema: the only game is invite-only, so she hears nothing.
		assertThat(notices(ama)).isEmpty();
		// Yaw hosted lately and has nothing on: asked to put one up.
		assertThat(notice(yaw).title()).isEqualTo("Nothing on near you this weekend");
		assertThat(notice(yaw).link()).isEqualTo("/games/new");
		// Nii hosts both games: nothing to tell him, and he's busy.
		assertThat(notices(nii)).isEmpty();

		// Once a weekend, however often it runs.
		assertThat(nudge.send()).isZero();
		assertThat(notices(kojo)).hasSize(1);
	}

	@Test
	void byEmailOnlyToThoseWhoAgreedAndLeftItOn() {
		email(kojo, "kojo@playchale.com", true, true);
		email(esi, "esi@playchale.com", true, false);
		email(yaw, "yaw@playchale.com", false, true);

		nudge.send();

		var mailed = jdbc.sql("""
				SELECT m.template || ':' || r.email FROM mailout_recipients r JOIN mailouts m ON m.id = r.mailout_id WHERE m.kind = 'weekend'
				""").query(String.class).list();
		assertThat(mailed).as("Esi turned the weekend email off; Yaw never said yes to hearing from PlayChale")
			.containsExactly("weekend:kojo@playchale.com");
		// Everyone still has it in the app.
		assertThat(notices(esi)).hasSize(1);
		assertThat(notices(yaw)).hasSize(1);
	}

	@Test
	void aGameThatsFullOrTheirsIsLeftOut() {
		// Beach volley, two spots: Nii's, and Ama takes the other.
		var volley = jdbc.sql("SELECT id FROM games WHERE title = 'Beach volley'").query(UUID.class).single();
		jdbc.sql("UPDATE games SET capacity = 2 WHERE id = :id").param("id", volley).update();
		games.join(volley, ama);
		nudge.send();
		assertThat(notice(kojo).title()).isEqualTo("A game near you this weekend");
	}

	private record Notice(String title, String body, String link) {
	}

	private Notice notice(UUID user) {
		return notices(user).getFirst();
	}

	private List<Notice> notices(UUID user) {
		return jdbc.sql("SELECT title, body, link FROM notifications WHERE user_id = :id AND kind = 'nearby'").param("id", user).query(Notice.class).list();
	}

	private UUID member(String phone, String name, String sports, String areas) {
		var id = users.registerOrFind(phone, "GH").id();
		jdbc.sql("""
				UPDATE users SET name = :name, onboarded = true, sports = CAST(:sports AS text[]), areas = CAST(:areas AS text[]),
				       area = (CAST(:areas AS text[]))[1]
				WHERE id = :id
				""").param("name", name).param("sports", sports).param("areas", areas).param("id", id).update();
		return id;
	}

	private UUID game(String title, String sport, Instant startsAt, String place, String area, String visibility, int capacity, UUID host) {
		var format = switch (sport) {
			case "football" -> "5-a-side";
			case "basketball" -> "5v5";
			case "volleyball" -> "Beach 2v2";
			default -> "Singles";
		};
		return games.create(new GameDetails(sport, format, title, startsAt, 60, "unlisted", null, null, place, area, null, capacity, 0, null, visibility,
				null), host).id();
	}

	private void email(UUID user, String address, boolean marketing, boolean weekend) {
		jdbc.sql("UPDATE users SET sign_in_email = :email WHERE id = :id").param("email", address).param("id", user).update();
		jdbc.sql("""
				INSERT INTO email_preferences (user_id, muted, marketing, digest, weekend, token, updated_at)
				VALUES (:id, '{}', :marketing, 'weekly', :weekend, gen_random_uuid(), now())
				""").param("id", user).param("marketing", marketing).param("weekend", weekend).update();
	}

}
