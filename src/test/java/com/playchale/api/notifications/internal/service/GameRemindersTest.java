package com.playchale.api.notifications.internal.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.games.internal.domain.GameDetails;
import com.playchale.api.games.internal.service.GameService;
import com.playchale.api.integration.sms.SmsSender;
import com.playchale.api.shared.TestClock;
import com.playchale.api.shared.config.PlaychaleProperties;
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
 * The evening before a game: everyone in it reminded in the app, and every Ghanaian number in it
 * texted (guests too, members unless they said no), from 6 pm to 9 pm in the game's time, once per
 * kick-off, in one plain SMS.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, GameRemindersTest.Fakes.class })
class GameRemindersTest {

	/** Keeps every text instead of sending it. */
	static class Texts implements SmsSender {

		final List<String> sent = new ArrayList<>();

		String last;

		@Override
		public void send(String phone, String message) {
			sent.add(phone);
			last = message;
		}

	}

	@TestConfiguration
	static class Fakes {

		@Bean
		@Primary
		Texts texts() {
			return new Texts();
		}

		@Bean
		@Primary
		TestClock testClock() {
			return new TestClock();
		}

	}

	/** Friday 16 October 2026 in Accra (UTC all year), a minute before the reminders go. */
	private static final Instant FRIDAY_EVENING = Instant.parse("2026-10-16T17:59:00Z");

	/** Saturday, 10 am in Accra. */
	private static final Instant KICKOFF = Instant.parse("2026-10-17T10:00:00Z");

	@Autowired
	GameReminders reminders;

	@Autowired
	TextSettings textSettings;

	@Autowired
	GameService games;

	@Autowired
	UserDirectory users;

	@Autowired
	Texts texts;

	@Autowired
	PlaychaleProperties properties;

	@Autowired
	TestClock clock;

	@Autowired
	JdbcClient jdbc;

	UUID kwame;

	UUID kojo;

	UUID ama;

	UUID chidi;

	@BeforeEach
	void setUp() {
		jdbc.sql("TRUNCATE users, sessions, games, game_participants, notifications, game_reminders, text_preferences, rate_limits CASCADE")
			.update();
		texts.sent.clear();
		texts.last = null;
		clock.set(FRIDAY_EVENING.minus(Duration.ofHours(6)));
		kwame = member("+233244555601", "Kwame Mensah", "GH");
		kojo = member("+233244555602", "Kojo Owusu", "GH");
		ama = member("+233244555603", "Ama Serwaa", "GH");
		chidi = member("+447700900123", "Chidi Okafor", "GB");
	}

	private UUID member(String phone, String name, String country) {
		var id = users.registerOrFind(phone, country).id();
		jdbc.sql("UPDATE users SET name = :name, onboarded = true WHERE id = :id").param("name", name).param("id", id).update();
		return id;
	}

	private UUID game(String title, Instant startsAt) {
		return games.create(new GameDetails("football", "5-a-side", title, startsAt, 60, "unlisted", null, null, "Legon Park", "Legon", null,
				10, 0, null, "public", null), kwame).id();
	}

	private List<String> reminded(UUID user) {
		return jdbc.sql("SELECT title || ' | ' || body FROM notifications WHERE user_id = :id AND kind = 'game-reminder'").param("id", user)
			.query(String.class).list();
	}

	@Test
	void everyoneIsRemindedTheEveningBeforeAndGhanaianNumbersAreTexted() {
		var saturday = game("Saturday 5s", KICKOFF);
		games.join(saturday, kojo);
		games.join(saturday, ama);
		games.join(saturday, chidi);
		games.addGuest(saturday, "Yaw from church", "024 455 5199", kwame);
		textSettings.setReminders(ama, false);

		clock.set(FRIDAY_EVENING);
		assertThat(reminders.send()).as("not before 6 pm").isZero();

		clock.set(FRIDAY_EVENING.plus(Duration.ofMinutes(1)));
		assertThat(reminders.send()).isEqualTo(1);
		assertThat(reminded(kwame)).containsExactly("Tomorrow: Saturday 5s | 10:00 am at Legon Park · 5 of 10 in.");
		assertThat(reminded(kojo)).containsExactly("Tomorrow: Saturday 5s | 10:00 am at Legon Park. See you there.");
		assertThat(reminded(ama)).as("in the app, whatever she said about texts").hasSize(1);
		assertThat(reminded(chidi)).hasSize(1);
		// Kwame, Kojo and the guest; not Ama (she said no), not Chidi (a UK number texts don't reach).
		assertThat(texts.sent).containsExactlyInAnyOrder("+233244555601", "+233244555602", "+233244555199");
		assertThat(texts.last).isEqualTo("PlayChale: Saturday 5s is tomorrow, 10:00am at Legon Park. Details: %s/games/%s"
			.formatted(properties.webApp().replaceFirst("^https?://", ""), saturday));

		// Once, however often it runs.
		clock.set(FRIDAY_EVENING.plus(Duration.ofMinutes(16)));
		assertThat(reminders.send()).isZero();
		assertThat(reminded(kojo)).hasSize(1);
		assertThat(texts.sent).hasSize(3);
	}

	@Test
	void notAGameCalledOffOrNotTomorrowAndNobodyAfterNine() {
		var off = game("Called off", KICKOFF);
		games.join(off, kojo);
		games.cancel(off, "Pitch flooded", kwame);
		var sunday = game("Sunday 5s", KICKOFF.plus(Duration.ofDays(1)));
		games.join(sunday, kojo);

		clock.set(FRIDAY_EVENING.plus(Duration.ofMinutes(1)));
		assertThat(reminders.send()).isZero();
		assertThat(reminded(kojo)).isEmpty();

		// Saturday at 9 pm: Sunday's game is tomorrow now, but it's too late to text anyone.
		clock.set(Instant.parse("2026-10-17T21:00:00Z"));
		assertThat(reminders.send()).isZero();
		clock.set(Instant.parse("2026-10-17T20:45:00Z"));
		assertThat(reminders.send()).isEqualTo(1);
		assertThat(reminded(kojo)).containsExactly("Tomorrow: Sunday 5s | 10:00 am at Legon Park. See you there.");
	}

	@Test
	void aGameMovedToAnotherDayIsRemindedAgainForThatDay() {
		var saturday = game("Saturday 5s", KICKOFF);
		games.join(saturday, kojo);
		clock.set(FRIDAY_EVENING.plus(Duration.ofMinutes(1)));
		assertThat(reminders.send()).isEqualTo(1);

		games.change(saturday, new GameService.Change("5-a-side", "Saturday 5s", null, KICKOFF.plus(Duration.ofDays(1)), 60, 10, 0, null,
				"public", null, null), kwame);
		clock.set(Instant.parse("2026-10-17T18:00:00Z"));
		assertThat(reminders.send()).isEqualTo(1);
		assertThat(reminded(kojo)).hasSize(2);
	}

	@Test
	void aMemberSeesWhereTextsGoAndCanTurnThemOff() {
		assertThat(textSettings.settings(kojo)).isEqualTo(new TextSettings.Settings(true, "+233244555602"));
		assertThat(textSettings.setReminders(kojo, false)).isEqualTo(new TextSettings.Settings(false, "+233244555602"));
		assertThat(textSettings.setReminders(kojo, true).reminders()).isTrue();
		// A UK number: there's nowhere texts reach.
		assertThat(textSettings.settings(chidi).phone()).isNull();
	}

	@Test
	void theTextIsOnePlainSmsWhateverTheGameIsCalled() {
		var text = GameReminders.text("Kwame’s “Friday Night” 7-a-side — staff vs management, everyone welcome, bring water",
				"6:00 pm", "Accra Sports Stadium training pitch behind the main stand, Osu – Accra", "https://playchale.com/games/"
						+ UUID.fromString("3f2b8c1e-6a7d-4e2f-9b1a-0c5d7e8f9a10"));
		assertThat(text).hasSizeLessThanOrEqualTo(GameReminders.SMS_LENGTH);
		assertThat(text).matches("[A-Za-z0-9 .,:;!?'\"()&@#%*+=/_\\-]+");
		assertThat(text).startsWith("PlayChale: Kwame's \"Friday Night\" 7-a-side").contains(" is tomorrow, 6:00pm at Accra Sports Stadium. Details: ")
			.endsWith("playchale.com/games/3f2b8c1e-6a7d-4e2f-9b1a-0c5d7e8f9a10").doesNotContain("..");
	}

}
