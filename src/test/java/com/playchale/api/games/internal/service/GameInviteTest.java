package com.playchale.api.games.internal.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.games.api.GameResponse;
import com.playchale.api.games.internal.domain.GameDetails;
import com.playchale.api.notifications.internal.service.NotificationResponse;
import com.playchale.api.notifications.internal.service.NotificationService;
import com.playchale.api.shared.TestClock;
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

/** Invites with answers: nobody is in a game without saying yes, and the host sees who said what. */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, GameInviteTest.Clocks.class })
class GameInviteTest {

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
	TeamService teams;

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

	UUID esi;

	@BeforeEach
	void setUp() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, games, game_participants, game_invites, notifications, teams, team_members CASCADE")
			.update();
		clock.set(NOW);
		kwame = user("+233244555123", "Kwame Mensah");
		kojo = user("+233244555124", "Kojo Owusu");
		ama = user("+233244555125", "Ama Serwaa");
		esi = user("+233244555127", "Esi Mensah");
	}

	private UUID user(String phone, String name) {
		var id = users.registerOrFind(phone, "GH").id();
		jdbc.sql("UPDATE users SET name = :name WHERE id = :id").param("name", name).param("id", id).update();
		return id;
	}

	private GameResponse game(int capacity, long totalCost) {
		return games.create(new GameDetails("football", "5-a-side", "Saturday 5s", KICKOFF, 60, "unlisted", null, null, "Legon Park", "Legon",
				null, capacity, totalCost, null, "public", null), kwame);
	}

	private List<String> titlesFor(UUID user) {
		return notifications.list(user).stream().map(NotificationResponse::title).toList();
	}

	private static String statusOf(GameResponse game, UUID user) {
		return game.invites().stream().filter(i -> i.userId().equals(user)).findFirst().map(GameResponse.InviteResponse::status).orElse(null);
	}

	@Test
	void eachPlayerAnswersAndTheHostSeesWhoSaidWhat() {
		var game = game(10, 0);
		assertThat(games.invite(game.id(), List.of(ama, esi, kwame), kwame)).as("not the host").isEqualTo(2);
		assertThat(titlesFor(ama)).containsExactly("Kwame invited you to Saturday 5s");
		assertThat(games.get(game.id(), kwame).invites()).extracting(GameResponse.InviteResponse::status).containsExactly("pending", "pending");
		assertThat(games.get(game.id(), ama).invites()).as("an invitee sees only their own").singleElement()
			.satisfies(i -> assertThat(i.userId()).isEqualTo(ama));
		assertThat(games.get(game.id(), kojo).invites()).as("nobody else sees invites").isNull();
		assertThat(games.invitations(ama)).extracting(GameResponse::id).containsExactly(game.id());

		var in = games.answerInvite(game.id(), true, ama);
		assertThat(in.participants()).extracting(GameResponse.ParticipantResponse::userId).contains(ama.toString());
		assertThat(games.invitations(ama)).isEmpty();

		games.answerInvite(game.id(), false, esi);
		assertThat(titlesFor(kwame)).contains("Esi can’t make Saturday 5s");
		var seen = games.get(game.id(), kwame);
		assertThat(statusOf(seen, ama)).isEqualTo("accepted");
		assertThat(statusOf(seen, esi)).isEqualTo("declined");
		assertThat(seen.participants()).extracting(GameResponse.ParticipantResponse::userId).doesNotContain(esi.toString());

		assertThatThrownBy(() -> games.answerInvite(game.id(), false, ama)).hasMessage("You’re in this game. Leave it instead.");
		assertThatThrownBy(() -> games.answerInvite(game.id(), true, kojo)).hasMessage("You don’t have an invite to this game.");

		games.leave(game.id(), ama);
		assertThat(statusOf(games.get(game.id(), kwame), ama)).as("in, then out: can't make it after all").isEqualTo("declined");

		games.invite(game.id(), List.of(esi), kwame);
		assertThat(statusOf(games.get(game.id(), kwame), esi)).as("asked again").isEqualTo("pending");
		games.join(game.id(), esi);
		assertThat(statusOf(games.get(game.id(), kwame), esi)).as("joining is saying yes").isEqualTo("accepted");
	}

	@Test
	void acceptingFollowsTheUsualRules() {
		var game = game(2, 0);
		games.invite(game.id(), List.of(ama, esi), kwame);
		games.answerInvite(game.id(), true, ama);
		assertThatThrownBy(() -> games.answerInvite(game.id(), true, esi)).hasMessage("Sorry, this game just filled up.");
		assertThatThrownBy(() -> games.invite(game.id(), List.of(kojo), kwame)).hasMessage("The game is full. There’s no spot to offer.");
		assertThat(statusOf(games.get(game.id(), kwame), esi)).isEqualTo("pending");
		assertThatThrownBy(() -> games.invite(game.id(), List.of(kojo), ama)).hasMessage("Only the host can do that.");
	}

	@Test
	void aHostInvitesATeamTheyreIn() {
		var ballers = teams.create("Osu Ballers", null, List.of(ama, esi, kwame), kojo);
		var game = game(10, 2000);
		var mine = teams.create("Friday Fives", null, List.of(), kwame);

		var strangers = teams.create("Labone United", null, List.of(), esi);
		assertThatThrownBy(() -> games.inviteTeam(game.id(), strangers.id(), kwame)).hasMessage("You can only invite a team you’re in.");
		assertThatThrownBy(() -> games.inviteTeam(game.id(), mine.id(), kwame)).hasMessage("Everyone in Friday Fives is already in the game.");

		assertThat(games.inviteTeam(game.id(), ballers.id(), kwame)).as("Kojo, Ama and Esi: not Kwame, who hosts").isEqualTo(3);
		assertThat(titlesFor(ama)).contains("Kwame invited Osu Ballers to Saturday 5s");
		assertThat(games.get(game.id(), kwame).invites()).allSatisfy(i -> {
			assertThat(i.teamName()).isEqualTo("Osu Ballers");
			assertThat(i.status()).isEqualTo("pending");
		});
		assertThat(games.get(game.id(), kwame).participants()).as("nobody is put in, or owes a share, without saying yes").hasSize(1);
	}

}
