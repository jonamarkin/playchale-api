package com.playchale.api.competitions.internal.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.competitions.internal.domain.CompetitionDetails;
import com.playchale.api.games.api.GameResponse;
import com.playchale.api.games.internal.domain.ResultInput;
import com.playchale.api.games.internal.service.GameService;
import com.playchale.api.games.internal.service.ResultService;
import com.playchale.api.notifications.internal.service.NotificationResponse;
import com.playchale.api.notifications.internal.service.NotificationService;
import com.playchale.api.profiles.internal.service.PlayerProfileService;
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

/** A league from setup to table against a real Postgres, with a clock we move. */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, CompetitionServiceTest.Clocks.class })
class CompetitionServiceTest {

	@TestConfiguration
	static class Clocks {

		@Bean
		@Primary
		TestClock testClock() {
			return new TestClock();
		}

	}

	private static final Instant NOW = Instant.parse("2026-09-25T08:00:00Z");

	private static final Instant FIRST_MATCHDAY = Instant.parse("2026-10-03T09:00:00Z");

	@Autowired
	CompetitionService competitions;

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

	UUID organiser;

	UUID kojo;

	UUID ama;

	UUID yaw;

	UUID esi;

	@BeforeEach
	void setUp() {
		jdbc.sql("""
				TRUNCATE users, sign_in_codes, sessions, games, game_participants, notifications, game_results,
				         competitions, teams, team_players, join_requests CASCADE
				""").update();
		clock.set(NOW);
		organiser = user("+233244555120", "Sam Addo");
		kojo = user("+233244555124", "Kojo Owusu");
		ama = user("+233244555125", "Ama Serwaa");
		yaw = user("+233244555126", "Yaw Boateng");
		esi = user("+233244555127", "Esi Mensah");
	}

	private UUID user(String phone, String name) {
		var id = users.registerOrFind(phone, "GH").id();
		jdbc.sql("UPDATE users SET name = :name WHERE id = :id").param("name", name).param("id", id).update();
		return id;
	}

	private CompetitionResponse league() {
		return competitions.create(new CompetitionDetails("Office League", "football", "5-a-side", "unlisted", null, "Legon Park", "Legon",
				FIRST_MATCHDAY, 60), organiser);
	}

	/** Three teams captained by Kojo, Ama and Yaw. */
	private CompetitionResponse withTeams() {
		var league = league();
		competitions.addTeam(league.id(), "Reds", kojo, List.of(kojo), organiser);
		competitions.addTeam(league.id(), "Blues", ama, List.of(ama), organiser);
		return competitions.addTeam(league.id(), "Greens", yaw, List.of(yaw), organiser);
	}

	private CompetitionResponse.TeamView team(CompetitionResponse league, String name) {
		return league.teams().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
	}

	private List<String> titlesFor(UUID user) {
		return notifications.list(user).stream().map(NotificationResponse::title).toList();
	}

	@Test
	void drawingFixturesMakesAGameForEveryPairing() {
		var league = withTeams();
		assertThatThrownBy(() -> competitions.generateFixtures(league.id(), kojo)).hasMessage("Only the organiser can change this league.");

		var drawn = competitions.generateFixtures(league.id(), organiser);
		assertThat(drawn.status()).isEqualTo("running");
		assertThat(drawn.rounds()).as("three teams: three rounds, one resting each week").isEqualTo(3);
		assertThat(drawn.fixtures()).hasSize(3).allSatisfy(f -> {
			assertThat(f.totalCost()).isZero();
			assertThat(f.hostId()).isEqualTo(organiser);
			assertThat(f.fixtureTeams()).isNotNull();
			assertThat(f.players()).hasSize(2);
		});
		assertThat(drawn.fixtures()).extracting(GameResponse::startsAt)
			.containsExactly(FIRST_MATCHDAY, FIRST_MATCHDAY.plus(Duration.ofDays(7)), FIRST_MATCHDAY.plus(Duration.ofDays(14)));
		assertThat(titlesFor(kojo)).containsExactly("Office League: fixtures are out");

		assertThatThrownBy(() -> competitions.generateFixtures(league.id(), organiser)).hasMessage("The fixtures are already drawn.");
		assertThatThrownBy(() -> competitions.addTeam(league.id(), "Golds", null, List.of(), organiser))
			.hasMessage("The fixtures are drawn. Add teams before drawing them, or start a new league.");
	}

	@Test
	void aSquadLinkIsSecretAndJoiningByItReachesUpcomingFixtures() {
		var league = competitions.generateFixtures(withTeams().id(), organiser);
		assertThat(team(competitions.get(league.id(), esi).orElseThrow(), "Reds").joinToken()).as("hidden from others").isEmpty();
		var token = team(competitions.get(league.id(), kojo).orElseThrow(), "Reds").joinToken();
		assertThat(token).as("the captain sees it").isNotEmpty();

		assertThatThrownBy(() -> competitions.joinWithToken(league.id(), "wrong", esi))
			.hasMessage("That squad link doesn’t work any more. Ask the captain for a new one.");
		var joined = competitions.joinWithToken(league.id(), token, esi);
		assertThat(team(joined, "Reds").playerIds()).containsExactly(kojo, esi);
		assertThat(titlesFor(kojo)).contains("Esi joined Reds");
		assertThat(joined.fixtures().stream().filter(f -> f.title().contains("Reds")))
			.allSatisfy(f -> assertThat(f.players()).extracting(GameResponse.PlayerResponse::name).contains("Esi Mensah"));

		var blues = team(joined, "Blues").id();
		assertThatThrownBy(() -> competitions.requestJoin(league.id(), blues, esi)).hasMessage("You’re already playing in this league.");
		assertThat(profiles.get(esi, Optional.empty()).teams()).containsExactly("Reds");
	}

	@Test
	void captainsAnswerRequestsAndRunTheirOwnSquads() {
		var league = withTeams();
		var reds = team(league, "Reds").id();
		competitions.requestJoin(league.id(), reds, esi);
		assertThatThrownBy(() -> competitions.requestJoin(league.id(), reds, esi)).hasMessage("Reds already has your request.");
		assertThat(titlesFor(kojo)).containsExactly("Esi wants to play for Reds");

		var request = competitions.get(league.id(), kojo).orElseThrow().requests().getFirst().id();
		assertThatThrownBy(() -> competitions.answerRequest(league.id(), request, true, ama))
			.hasMessage("Only Kojo or the organiser can change this squad.");
		var answered = competitions.answerRequest(league.id(), request, true, kojo);
		assertThat(team(answered, "Reds").playerIds()).contains(esi);
		assertThat(answered.requests()).isEmpty();
		assertThat(titlesFor(esi)).containsExactly("You’re in Reds");

		assertThatThrownBy(() -> competitions.removePlayer(league.id(), reds, kojo, kojo))
			.hasMessage("The captain can’t be dropped. Hand the armband over first.");
		assertThatThrownBy(() -> competitions.addPlayers(league.id(), team(answered, "Blues").id(), List.of(esi), ama))
			.hasMessage("Those players are already in a team in this league.");
		competitions.removePlayer(league.id(), reds, esi, kojo);
		assertThat(team(competitions.addPlayers(league.id(), team(answered, "Blues").id(), List.of(esi), ama), "Blues").playerIds())
			.containsExactly(ama, esi);
	}

	@Test
	void theTableCountsPlayedFixturesByTheLeaguesRules() {
		var league = competitions.generateFixtures(withTeams().id(), organiser);
		var first = league.fixtures().getFirst();
		clock.set(first.startsAt().plus(Duration.ofHours(2)));
		var home = first.fixtureTeams().home();
		var away = first.fixtureTeams().away();
		results.record(first.id(), new ResultInput(2, 0, home.playerIds().stream().map(UUID::toString).toList(),
				away.playerIds().stream().map(UUID::toString).toList(), null, null, null), organiser);

		var table = competitions.get(league.id(), null).orElseThrow().table();
		assertThat(table.getFirst().team().name()).isEqualTo(home.name());
		assertThat(table.getFirst().points()).isEqualTo(3);
		assertThat(table.getFirst().difference()).isEqualTo(2);
		assertThat(table.getFirst().form()).containsExactly("W");
		assertThat(table.getLast().team().name()).isEqualTo(away.name());
		assertThat(table.getLast().lost()).isEqualTo(1);
	}

	@Test
	void leaguesNeedThreeTeamsAndUniqueNames() {
		var league = league();
		competitions.addTeam(league.id(), "Reds", kojo, List.of(), organiser);
		assertThatThrownBy(() -> competitions.addTeam(league.id(), "reds", ama, List.of(), organiser)).hasMessage("reds is already in this league.");
		assertThatThrownBy(() -> competitions.addTeam(league.id(), "Blues", kojo, List.of(), organiser))
			.hasMessage("Kojo is already in another team in this league.");
		competitions.addTeam(league.id(), "Blues", ama, List.of(), organiser);
		assertThatThrownBy(() -> competitions.generateFixtures(league.id(), organiser)).hasMessage("A league needs at least three teams.");
		assertThat(competitions.list(null)).as("drafts aren't listed").isEmpty();
		assertThat(competitions.mine(kojo)).hasSize(1);
	}

}
