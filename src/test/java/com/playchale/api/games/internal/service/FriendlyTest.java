package com.playchale.api.games.internal.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.games.api.GameResponse;
import com.playchale.api.games.internal.domain.GameDetails;
import com.playchale.api.games.internal.domain.ResultInput;
import com.playchale.api.notifications.internal.service.NotificationResponse;
import com.playchale.api.notifications.internal.service.NotificationService;
import com.playchale.api.shared.TestClock;
import com.playchale.api.teams.internal.service.TeamResponse;
import com.playchale.api.teams.internal.service.TeamService;
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

/** Team-vs-team friendlies: the challenge, each side's players, and the result counting for both teams. */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, FriendlyTest.Clocks.class })
class FriendlyTest {

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
	ResultService results;

	@Autowired
	TeamService teams;

	@Autowired
	NotificationService notifications;

	@Autowired
	UserDirectory users;

	@Autowired
	TestClock clock;

	@Autowired
	JdbcClient jdbc;

	UUID kojo;

	UUID kwame;

	UUID esi;

	UUID ama;

	TeamResponse ballers;

	TeamResponse labone;

	@BeforeEach
	void setUp() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, games, game_participants, game_invites, game_results, notifications, teams, team_members CASCADE")
			.update();
		clock.set(NOW);
		kojo = user("+233244555124", "Kojo Owusu");
		kwame = user("+233244555123", "Kwame Mensah");
		esi = user("+233244555127", "Esi Mensah");
		ama = user("+233244555125", "Ama Serwaa");
		ballers = teams.create("Osu Ballers", null, List.of(kwame), kojo);
		labone = teams.create("Labone United", null, List.of(ama), esi);
	}

	private UUID user(String phone, String name) {
		var id = users.registerOrFind(phone, "GH").id();
		jdbc.sql("UPDATE users SET name = :name WHERE id = :id").param("name", name).param("id", id).update();
		return id;
	}

	private static GameDetails details() {
		return new GameDetails("football", "5-a-side", "", KICKOFF, 60, "unlisted", null, null, "Legon Park", "Legon", null, 10, 0, null,
				"public", null);
	}

	private List<String> titlesFor(UUID user) {
		return notifications.list(user).stream().map(NotificationResponse::title).toList();
	}

	private static List<String> side(GameResponse.TeamSide side) {
		return side.playerIds();
	}

	@Test
	void theOtherCaptainAcceptsThenEachSideAnswers() {
		assertThatThrownBy(() -> games.create(details(), ballers.id(), labone.id(), kwame))
			.hasMessage("Only Kojo can set up a game for Osu Ballers.");
		assertThatThrownBy(() -> games.create(details(), ballers.id(), ballers.id(), kojo))
			.hasMessage("A team can’t play itself. Pick another team to play.");
		assertThatThrownBy(() -> games.create(details(), ballers.id(), null, kojo)).hasMessage("Pick your team and the team you’re playing.");

		var game = games.create(details(), ballers.id(), labone.id(), kojo);
		assertThat(game.title()).isEqualTo("Osu Ballers vs Labone United");
		assertThat(game.friendly().opponentStatus()).isEqualTo("pending");
		assertThat(side(game.friendly().home())).containsExactly(kojo.toString());
		assertThat(titlesFor(kwame)).contains("Kojo invited Osu Ballers to Osu Ballers vs Labone United");
		assertThat(titlesFor(esi)).contains("Osu Ballers challenged Labone United");
		assertThat(titlesFor(ama)).as("nobody on the other team is asked before their captain says yes").doesNotContain(
				"Esi invited Labone United to Osu Ballers vs Labone United");

		games.answerInvite(game.id(), true, kwame);
		assertThatThrownBy(() -> games.answerChallenge(game.id(), true, ama)).hasMessage("Only Esi can answer for Labone United.");

		var accepted = games.answerChallenge(game.id(), true, esi);
		assertThat(accepted.friendly().opponentStatus()).isEqualTo("accepted");
		assertThat(side(accepted.friendly().away())).as("the captain plays for their side").containsExactly(esi.toString());
		assertThat(titlesFor(ama)).contains("Esi invited Labone United to Osu Ballers vs Labone United");
		assertThat(titlesFor(kojo)).contains("Labone United accepted your challenge");
		assertThatThrownBy(() -> games.answerChallenge(game.id(), true, esi)).hasMessage("That challenge has already been answered.");

		var full = games.answerInvite(game.id(), true, ama);
		assertThat(side(full.friendly().home())).containsExactly(kojo.toString(), kwame.toString());
		assertThat(side(full.friendly().away())).containsExactly(esi.toString(), ama.toString());

		assertThat(teams.get(labone.id(), esi).orElseThrow().upcoming()).singleElement()
			.satisfies(g -> assertThat(g.opponentName()).isEqualTo("Osu Ballers"));
		assertThatThrownBy(() -> teams.delete(labone.id(), esi))
			.hasMessage("Labone United has a game coming up. Call it off, or turn the challenge down, first.");

		clock.set(KICKOFF.plus(Duration.ofHours(2)));
		results.record(game.id(), new ResultInput(2, 1, side(full.friendly().home()), side(full.friendly().away()), null, null, null), kojo);
		var home = teams.get(ballers.id(), kojo).orElseThrow();
		assertThat(home.record().played()).isEqualTo(1);
		assertThat(home.record().won()).isEqualTo(1);
		assertThat(home.recent()).singleElement().satisfies(g -> {
			assertThat(g.outcome()).isEqualTo("W");
			assertThat(g.scoreFor()).isEqualTo(2);
		});
		var away = teams.get(labone.id(), esi).orElseThrow();
		assertThat(away.record().lost()).isEqualTo(1);
		assertThat(away.recent().getFirst().scoreAgainst()).isEqualTo(2);
		assertThat(away.upcoming()).isEmpty();
	}

	@Test
	void aTurnedDownChallengeLeavesTheOtherTeamOutOfIt() {
		var game = games.create(details(), ballers.id(), labone.id(), kojo);
		assertThat(teams.get(labone.id(), esi).orElseThrow().upcoming()).singleElement()
			.satisfies(g -> assertThat(g.challenge()).isEqualTo("pending"));

		var declined = games.answerChallenge(game.id(), false, esi);
		assertThat(declined.friendly().opponentStatus()).isEqualTo("declined");
		assertThat(titlesFor(kojo)).contains("Labone United can’t play");
		assertThat(teams.get(labone.id(), esi).orElseThrow().upcoming()).isEmpty();
		assertThat(titlesFor(ama)).as("its players were never asked").noneMatch(t -> t.contains("invited"));

		// Someone in Labone United joining anyway plays on no side: the host sorts sides on the day.
		assertThat(side(games.join(game.id(), ama).friendly().away())).isEmpty();
	}

	@Test
	void teamsCanBeFoundByName() {
		assertThat(teams.search("lab", kojo)).extracting(TeamResponse.Found::name).containsExactly("Labone United");
		assertThat(teams.search("l", kojo)).as("two letters at least").isEmpty();
		assertThat(teams.search("BALL", esi)).singleElement().satisfies(t -> {
			assertThat(t.memberCount()).isEqualTo(2);
			assertThat(t.captain().phone()).as("no one else's number").isEmpty();
		});
	}

}
