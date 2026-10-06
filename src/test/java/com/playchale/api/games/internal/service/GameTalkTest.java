package com.playchale.api.games.internal.service;

import java.time.Instant;
import java.util.UUID;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.games.api.GameMessageResponse;
import com.playchale.api.games.api.GameResponse;
import com.playchale.api.games.internal.domain.GameDetails;
import com.playchale.api.notifications.internal.service.NotificationResponse;
import com.playchale.api.notifications.internal.service.NotificationService;
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

/**
 * Talk about a game. Being in the game is the whole access rule and the whole moderation model, so
 * these are mostly tests that it holds from every side.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, GameTalkTest.Clocks.class })
class GameTalkTest {

	@TestConfiguration
	static class Clocks {

		@Bean
		@Primary
		TestClock testClock() {
			return new TestClock();
		}

	}

	private static final Instant NOW = Instant.parse("2026-09-25T08:00:00Z");

	private static final Instant KICKOFF = Instant.parse("2026-09-26T10:00:00Z");

	@Autowired
	GameService games;

	@Autowired
	GameTalk talk;

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
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, games, game_participants, game_messages, notifications CASCADE").update();
		clock.set(NOW);
		kwame = user("+233244555123", "Kwame Mensah");
		kojo = user("+233244555124", "Kojo Owusu");
		ama = user("+233244555125", "Ama Serwaa");
	}

	private UUID user(String phone, String name) {
		var id = users.registerOrFind(phone, "GH").id();
		jdbc.sql("UPDATE users SET name = :name WHERE id = :id").param("name", name).param("id", id).update();
		return id;
	}

	/** Hosted by Kwame, with Kojo in it. Ama is not. */
	private GameResponse game() {
		var game = games.create(new GameDetails("football", "5-a-side", "Saturday 5s", KICKOFF, 60, "unlisted", null, null, "Legon Park",
				"Legon", null, 10, 0, null, "public", null), kwame);
		games.join(game.id(), kojo);
		return game;
	}

	@Test
	void thePeopleInTheGameCanTalkAndEveryoneElseSeesTheySaidNothing() {
		var game = game();
		talk.say(game.id(), "Running ten minutes late", kojo);

		assertThat(talk.list(game.id(), kwame)).extracting(GameMessageResponse::body).containsExactly("Running ten minutes late");
		assertThat(talk.list(game.id(), kojo)).hasSize(1);

		// Ama holds no spot. She isn't told "no" in a way that confirms the game or who's in it.
		assertThatThrownBy(() -> talk.list(game.id(), ama)).hasMessage("That game could not be found.");
		assertThatThrownBy(() -> talk.say(game.id(), "hello", ama)).hasMessage("That game could not be found.");
	}

	@Test
	void talkDoesNotHandOutPeoplesNumbers() {
		var game = game();
		talk.say(game.id(), "On my way", kojo);

		var asKwame = talk.list(game.id(), kwame).get(0).said();
		assertThat(asKwame.name()).as("you see who said it").isEqualTo("Kojo Owusu");
		assertThat(asKwame.phone()).as("but not their number: a public game is one tap to join").isEmpty();
		assertThat(asKwame.email()).isNull();
		assertThat(asKwame.payoutPhone()).isNull();

		assertThat(talk.list(game.id(), kojo).get(0).said().phone()).as("your own is still yours").isNotEmpty();
	}

	@Test
	void theOthersInTheGameAreTold() {
		var game = game();
		talk.say(game.id(), "Bringing the bibs", kwame);

		assertThat(notifications.list(kojo)).extracting(NotificationResponse::body).contains("Bringing the bibs");
		assertThat(notifications.list(kwame)).as("not told about their own message")
			.extracting(NotificationResponse::body).doesNotContain("Bringing the bibs");
	}

	@Test
	void whoeverSaidItCanTakeItDown() {
		var game = game();
		var said = talk.say(game.id(), "Wrong game, sorry", kojo).get(0);
		assertThat(talk.remove(game.id(), said.id(), kojo)).isEmpty();
	}

	@Test
	void theHostCanTakeAnythingDownAndNobodyElseCan() {
		var game = game();
		var said = talk.say(game.id(), "Something out of order", kojo).get(0);

		// Another player in the game is not a moderator.
		games.join(game.id(), ama);
		assertThatThrownBy(() -> talk.remove(game.id(), said.id(), ama))
			.hasMessage("Only whoever said it, or the host, can take it down.");

		assertThat(talk.remove(game.id(), said.id(), kwame)).as("the host can").isEmpty();
	}

	@Test
	void nothingEmptyOrEndlessIsSaid() {
		var game = game();
		assertThatThrownBy(() -> talk.say(game.id(), "   ", kojo)).hasMessage("Say something first.");
		assertThatThrownBy(() -> talk.say(game.id(), "x".repeat(501), kojo))
			.hasMessage("That's longer than 500 characters. Keep it short.");
		assertThat(talk.list(game.id(), kojo)).isEmpty();
	}

	@Test
	void leavingTheGameTakesTheTalkWithIt() {
		var game = game();
		talk.say(game.id(), "See you there", kojo);
		games.leave(game.id(), kojo);
		assertThatThrownBy(() -> talk.list(game.id(), kojo)).as("no longer in it").hasMessage("That game could not be found.");
		assertThat(talk.list(game.id(), kwame)).as("what was said stays for those still in it").hasSize(1);
	}

	@Test
	void talkIsOldestFirstSoItReadsAsAConversation() {
		var game = game();
		talk.say(game.id(), "first", kwame);
		clock.set(NOW.plusSeconds(60));
		talk.say(game.id(), "second", kojo);
		assertThat(talk.list(game.id(), kwame)).extracting(GameMessageResponse::body).containsExactly("first", "second");
		assertThat(talk.list(game.id(), kwame)).extracting(m -> m.said().name()).containsExactly("Kwame Mensah", "Kojo Owusu");
	}

}
