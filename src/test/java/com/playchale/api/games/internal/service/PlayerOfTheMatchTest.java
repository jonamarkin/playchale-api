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
import com.playchale.api.notifications.internal.service.NotificationResponse;
import com.playchale.api.notifications.internal.service.NotificationService;
import com.playchale.api.profiles.internal.service.PlayerProfileService;
import com.playchale.api.shared.TestClock;
import com.playchale.api.users.api.AccountDeleted;
import com.playchale.api.users.api.GuestAccountMerged;
import com.playchale.api.users.api.UserDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The player of the match vote against a real Postgres: who can pick whom, what each viewer sees, and the announcement. */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, PlayerOfTheMatchTest.Clocks.class })
class PlayerOfTheMatchTest {

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
	PlayerOfTheMatch announcer;

	@Autowired
	PlayerProfileService profiles;

	@Autowired
	NotificationService notifications;

	@Autowired
	UserDirectory users;

	@Autowired
	ApplicationEventPublisher events;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	TestClock clock;

	@Autowired
	JdbcClient jdbc;

	UUID kwame;

	UUID kojo;

	UUID ama;

	UUID esi;

	GameResponse game;

	String yaw;

	@BeforeEach
	void setUp() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, games, game_participants, notifications, game_results CASCADE").update();
		clock.set(NOW);
		kwame = user("+233244555123", "Kwame Mensah");
		kojo = user("+233244555124", "Kojo Owusu");
		ama = user("+233244555125", "Ama Serwaa");
		esi = user("+233244555126", "Esi Badu");
		// Kwame hosts; Kojo, Ama and Esi join, and Kwame adds Yaw, who has no account.
		game = games.create(new GameDetails("football", "5-a-side", null, NOW.plus(Duration.ofHours(1)), 60, "unlisted", null, null,
				"Legon Park", null, null, 10, 0, null, "public", null), kwame);
		games.join(game.id(), kojo);
		games.join(game.id(), ama);
		games.join(game.id(), esi);
		yaw = "guest:" + games.addGuest(game.id(), "Yaw Boateng", null, kwame).spot();
		clock.set(NOW.plus(Duration.ofHours(3)));
	}

	private UUID user(String phone, String name) {
		var id = users.registerOrFind(phone, "GH").id();
		jdbc.sql("UPDATE users SET name = :name, sports = '{football}' WHERE id = :id").param("name", name).param("id", id).update();
		return id;
	}

	/** Kwame and Kojo against Ama and Yaw; Esi didn't turn up. */
	private GameResponse record() {
		return results.record(game.id(), new ResultInput(3, 1, List.of(kwame.toString(), kojo.toString()), List.of(ama.toString(), yaw),
				null, null, List.of(esi.toString())), kwame);
	}

	private GameResponse.Vote voteAs(UUID viewer) {
		return games.get(game.id(), viewer).result().vote();
	}

	private List<NotificationResponse> announcements(UUID user) {
		return notifications.list(user).stream().filter(n -> n.title().startsWith("Player of the match")).toList();
	}

	private int announce() {
		return tx.execute(status -> announcer.announce());
	}

	@Test
	void playersPickSomeoneElseWhoPlayedAndOnlySeeTheWinnerOnceVotingCloses() {
		assertThatThrownBy(() -> results.vote(game.id(), ama.toString(), kojo)).hasMessage("There’s no result to check yet.");
		record();

		assertThatThrownBy(() -> results.vote(game.id(), kojo.toString(), esi))
			.hasMessage("Only players who were in this game can pick the player of the match.");
		assertThatThrownBy(() -> results.vote(game.id(), kojo.toString(), kojo)).hasMessage("Pick someone else: you can’t vote for yourself.");
		assertThatThrownBy(() -> results.vote(game.id(), esi.toString(), kojo)).hasMessage("Pick someone who played in this game.");
		assertThatThrownBy(() -> results.vote(game.id(), "guest:nobody", kojo)).hasMessage("Pick someone who played in this game.");

		// Picking again changes it; a guest can be picked.
		results.vote(game.id(), ama.toString(), kojo);
		var mine = results.vote(game.id(), yaw, kojo).result().vote();
		assertThat(mine.open()).isTrue();
		assertThat(mine.closesAt()).isEqualTo(NOW.plus(Duration.ofHours(27)));
		assertThat(mine.myPick()).isEqualTo(yaw);
		assertThat(mine.cast()).isEqualTo(1);
		// Kwame, Kojo and Ama: Yaw has no account and Esi didn't play.
		assertThat(mine.eligible()).isEqualTo(3);

		results.vote(game.id(), yaw, ama);
		results.vote(game.id(), kojo.toString(), kwame);
		// Nobody sees who's ahead while it's open.
		var open = voteAs(ama);
		assertThat(open.cast()).isEqualTo(3);
		assertThat(open.myPick()).isEqualTo(yaw);
		assertThat(open.winners()).isNull();
		assertThat(open.votes()).isNull();
		assertThat(announce()).isZero();

		clock.set(NOW.plus(Duration.ofHours(27)));
		assertThatThrownBy(() -> results.vote(game.id(), ama.toString(), kwame)).hasMessage("Voting for the player of the match has closed.");
		var closed = voteAs(null);
		assertThat(closed.open()).isFalse();
		assertThat(closed.winners()).containsExactly(yaw);
		assertThat(closed.votes()).isEqualTo(2);
		assertThat(closed.myPick()).isNull();

		// Everyone who played hears, and the host; once.
		assertThat(announce()).isEqualTo(1);
		for (var player : List.of(kwame, kojo, ama)) {
			assertThat(announcements(player)).singleElement().satisfies(n -> {
				assertThat(n.title()).isEqualTo("Player of the match: Yaw");
				assertThat(n.body()).isEqualTo("Voted by the players in 5-a-side football.");
			});
		}
		assertThat(announcements(esi)).isEmpty();
		assertThat(announce()).isZero();
		assertThat(announcements(kojo)).hasSize(1);
	}

	@Test
	void levelOnVotesTheyShareIt() {
		record();
		results.vote(game.id(), ama.toString(), kojo);
		results.vote(game.id(), kojo.toString(), ama);
		// Nothing on profiles until voting closes.
		assertThat(profiles.get(kojo, Optional.empty()).stats().playerOfTheMatch()).isZero();

		clock.set(NOW.plus(Duration.ofHours(28)));
		// Both count it, on their profile and in their history; Kwame, who played too, doesn't.
		assertThat(profiles.get(kojo, Optional.empty()).stats().playerOfTheMatch()).isEqualTo(1);
		assertThat(profiles.get(ama, Optional.empty()).bySport().getFirst().stats().playerOfTheMatch()).isEqualTo(1);
		assertThat(profiles.history(kojo).getFirst().playerOfTheMatch()).isTrue();
		assertThat(profiles.history(kwame).getFirst().playerOfTheMatch()).isNull();
		assertThat(profiles.get(kwame, Optional.empty()).stats().playerOfTheMatch()).isZero();
		var closed = voteAs(kojo);
		assertThat(closed.winners()).containsExactly(kojo.toString(), ama.toString());
		assertThat(closed.votes()).isEqualTo(1);
		assertThat(closed.myPick()).isEqualTo(ama.toString());

		assertThat(announce()).isEqualTo(1);
		assertThat(announcements(kwame)).singleElement().satisfies(n -> {
			assertThat(n.title()).isEqualTo("Player of the match: Kojo and Ama");
			assertThat(n.body()).isEqualTo("Level on votes in 5-a-side football: they share it.");
		});
		// The winners hear it as theirs, with their match card a tap away.
		assertThat(notifications.list(kojo).stream().filter(n -> n.title().equals("You share player of the match")).toList()).singleElement()
			.satisfies(n -> {
				assertThat(n.body()).isEqualTo("Level on votes in 5-a-side football. Share your match card.");
				assertThat(n.link()).isEqualTo("/games/%s?card=1".formatted(game.id()));
			});
	}

	@Test
	void aCorrectionDropsPicksForAndByAnyoneTakenOffASideWithoutReopeningTheVote() {
		record();
		results.vote(game.id(), ama.toString(), kojo);
		results.vote(game.id(), kojo.toString(), ama);
		results.vote(game.id(), ama.toString(), kwame);

		// Ama didn't play after all.
		clock.set(NOW.plus(Duration.ofHours(10)));
		var corrected = results.record(game.id(), new ResultInput(3, 1, List.of(kwame.toString(), kojo.toString()), List.of(yaw),
				null, null, List.of(esi.toString(), ama.toString())), kwame).result().vote();
		assertThat(corrected.cast()).isZero();
		assertThat(corrected.eligible()).isEqualTo(2);
		assertThat(corrected.closesAt()).isEqualTo(NOW.plus(Duration.ofHours(27)));

		results.vote(game.id(), yaw, kojo);
		clock.set(NOW.plus(Duration.ofHours(27)));
		assertThat(voteAs(kwame).winners()).containsExactly(yaw);
	}

	@Test
	void withNoPicksNobodyHearsAnything() {
		record();
		clock.set(NOW.plus(Duration.ofHours(30)));
		var closed = voteAs(kojo);
		assertThat(closed.open()).isFalse();
		assertThat(closed.cast()).isZero();
		assertThat(closed.winners()).isNull();
		assertThat(announce()).isZero();
		assertThat(announcements(kojo)).isEmpty();
	}

	@Test
	void aClosedAccountsPicksGoAndAMergedGuestsMoveWithThem() {
		record();
		results.vote(game.id(), ama.toString(), kojo);
		clock.advance(Duration.ofMinutes(1));
		results.vote(game.id(), kojo.toString(), ama);

		// Ama's account is merged into Esi's: her pick is Esi's now, and so is the pick for her.
		tx.executeWithoutResult(status -> events.publishEvent(new GuestAccountMerged(ama, esi)));
		assertThat(jdbc.sql("SELECT voter_id::text || '>' || player_key FROM result_votes ORDER BY voted_at").query(String.class).list())
			.containsExactly(kojo + ">" + esi, esi + ">" + kojo);

		tx.executeWithoutResult(status -> events.publishEvent(new AccountDeleted(kojo, null, null)));
		assertThat(jdbc.sql("SELECT voter_id::text || '>' || player_key FROM result_votes").query(String.class).list())
			.containsExactly(esi + ">" + kojo);
	}

}
