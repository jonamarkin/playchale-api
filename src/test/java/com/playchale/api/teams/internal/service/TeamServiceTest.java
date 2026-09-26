package com.playchale.api.teams.internal.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.competitions.internal.domain.CompetitionDetails;
import com.playchale.api.competitions.internal.service.CompetitionResponse;
import com.playchale.api.competitions.internal.service.CompetitionService;
import com.playchale.api.notifications.internal.service.NotificationResponse;
import com.playchale.api.notifications.internal.service.NotificationService;
import com.playchale.api.profiles.internal.service.PlayerProfileService;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.users.api.UserSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Standing teams against a real Postgres: joining, captains' tools, and entering leagues. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TeamServiceTest {

	@Autowired
	TeamService teams;

	@Autowired
	CompetitionService competitions;

	@Autowired
	PlayerProfileService profiles;

	@Autowired
	NotificationService notifications;

	@Autowired
	UserDirectory users;

	@Autowired
	JdbcClient jdbc;

	UUID kojo;

	UUID ama;

	UUID yaw;

	UUID esi;

	UUID sam;

	@BeforeEach
	void setUp() {
		jdbc.sql("""
				TRUNCATE users, sign_in_codes, sessions, games, game_participants, notifications, game_results,
				         competitions, teams, team_members, team_join_requests, competition_entries, entry_players CASCADE
				""").update();
		kojo = user("+233244555124", "Kojo Owusu");
		ama = user("+233244555125", "Ama Serwaa");
		yaw = user("+233244555126", "Yaw Boateng");
		esi = user("+233244555127", "Esi Mensah");
		sam = user("+233244555120", "Sam Addo");
	}

	private UUID user(String phone, String name) {
		var id = users.registerOrFind(phone, "GH").id();
		jdbc.sql("UPDATE users SET name = :name WHERE id = :id").param("name", name).param("id", id).update();
		return id;
	}

	private List<String> titlesFor(UUID user) {
		return notifications.list(user).stream().map(NotificationResponse::title).toList();
	}

	private static List<String> names(TeamResponse team) {
		return team.members().stream().map(UserSummary::name).toList();
	}

	@Test
	void whoeverSetsATeamUpCaptainsItAndPlaysForIt() {
		assertThatThrownBy(() -> teams.create("  ", null, List.of(), kojo)).hasMessage("Give the team a name.");

		var team = teams.create(" Osu Ballers ", null, List.of(ama, UUID.randomUUID()), kojo);
		assertThat(team.name()).isEqualTo("Osu Ballers");
		assertThat(team.captainId()).isEqualTo(kojo);
		assertThat(team.tint()).as("the first colour when none is picked").isNotBlank();
		assertThat(names(team)).as("strangers' ids are ignored").containsExactly("Kojo Owusu", "Ama Serwaa");
		assertThat(team.joinToken()).isNotEmpty();
		assertThat(titlesFor(ama)).containsExactly("You’re in Osu Ballers");

		var seen = teams.get(team.id(), esi).orElseThrow();
		assertThat(seen.joinToken()).as("only the captain sees the link").isEmpty();
		assertThat(seen.requests()).isNull();
		assertThat(teams.mine(ama)).extracting(TeamResponse::name).containsExactly("Osu Ballers");
		assertThat(profiles.get(ama, Optional.empty()).teams()).containsExactly("Osu Ballers");
	}

	@Test
	void joiningByLinkOrByAsking() {
		var team = teams.create("Osu Ballers", null, List.of(), kojo);
		assertThatThrownBy(() -> teams.joinWithToken(team.id(), "wrong", esi))
			.hasMessage("That team link doesn’t work any more. Ask the captain for a new one.");
		assertThat(names(teams.joinWithToken(team.id(), team.joinToken(), esi))).contains("Esi Mensah");
		assertThat(titlesFor(kojo)).containsExactly("Esi joined Osu Ballers");
		assertThatThrownBy(() -> teams.joinWithToken(team.id(), team.joinToken(), esi)).hasMessage("You’re already in Osu Ballers.");

		assertThat(teams.requestJoin(team.id(), yaw).requested()).isTrue();
		assertThatThrownBy(() -> teams.requestJoin(team.id(), yaw)).hasMessage("Osu Ballers already has your request.");
		assertThat(titlesFor(kojo)).contains("Yaw wants to join Osu Ballers");

		var request = teams.get(team.id(), kojo).orElseThrow().requests().getFirst().id();
		assertThatThrownBy(() -> teams.answerRequest(team.id(), request, true, esi)).hasMessage("Only Kojo can answer requests for Osu Ballers.");
		var answered = teams.answerRequest(team.id(), request, true, kojo);
		assertThat(names(answered)).contains("Yaw Boateng");
		assertThat(answered.requests()).isEmpty();
		assertThat(titlesFor(yaw)).containsExactly("You’re in Osu Ballers");
		assertThatThrownBy(() -> teams.answerRequest(team.id(), request, true, kojo)).hasMessage("That request has already been answered.");

		teams.requestJoin(team.id(), ama);
		var no = teams.get(team.id(), kojo).orElseThrow().requests().getFirst().id();
		assertThat(names(teams.answerRequest(team.id(), no, false, kojo))).doesNotContain("Ama Serwaa");
		assertThat(titlesFor(ama)).containsExactly("Osu Ballers is full for now");
	}

	@Test
	void theCaptainRunsTheTeam() {
		var team = teams.create("Osu Ballers", null, List.of(ama), kojo);
		assertThatThrownBy(() -> teams.update(team.id(), "Ama's Team", null, null, ama)).hasMessage("Only Kojo can change the team.");
		assertThatThrownBy(() -> teams.addMembers(team.id(), List.of(esi), ama)).hasMessage("Only Kojo can change the team.");
		assertThatThrownBy(() -> teams.update(team.id(), null, "#000000", null, kojo)).hasMessage("Pick one of the team colours.");
		assertThatThrownBy(() -> teams.update(team.id(), null, null, esi, kojo)).hasMessage("Pick someone in the team to be captain.");

		assertThat(names(teams.addMembers(team.id(), List.of(esi), kojo))).containsExactly("Kojo Owusu", "Ama Serwaa", "Esi Mensah");
		assertThatThrownBy(() -> teams.addMembers(team.id(), List.of(esi), kojo)).hasMessage("They’re already in Osu Ballers.");
		assertThatThrownBy(() -> teams.removeMember(team.id(), esi, ama)).hasMessage("Only Kojo can change the team.");
		assertThat(names(teams.removeMember(team.id(), esi, esi))).as("anyone can leave").doesNotContain("Esi Mensah");
		assertThatThrownBy(() -> teams.removeMember(team.id(), kojo, kojo))
			.hasMessage("The captain can’t leave. Hand the armband to someone else first.");

		var handedOver = teams.update(team.id(), "Osu Ballers FC", "#a9c4f2", ama, kojo);
		assertThat(handedOver.captainId()).isEqualTo(ama);
		assertThat(handedOver.name()).isEqualTo("Osu Ballers FC");
		assertThat(handedOver.joinToken()).as("the link moves with the armband").isEmpty();
		assertThat(names(teams.removeMember(team.id(), kojo, kojo))).containsExactly("Ama Serwaa");
	}

	@Test
	void aTeamOnlyGoesIntoAnotherOrganisersLeagueWhenItsCaptainAccepts() {
		var ballers = teams.create("Osu Ballers", null, List.of(ama), kojo);
		var mine = teams.create("Sam's Six", null, List.of(esi), sam);
		var league = competitions.create(new CompetitionDetails("Office League", "football", "5-a-side", "unlisted", null, "Legon Park",
				"Legon", null, Instant.parse("2030-06-01T09:00:00Z"), 60), sam);

		var withMine = competitions.addTeam(league.id(), mine.id(), null, null, null, sam);
		assertThat(team(withMine, "Sam's Six").status()).as("the organiser's own team goes straight in").isNull();
		assertThat(team(withMine, "Sam's Six").playerIds()).containsExactly(sam, esi);

		var invited = competitions.addTeam(league.id(), ballers.id(), null, null, null, sam);
		assertThat(team(invited, "Osu Ballers").status()).isEqualTo("invited");
		assertThat(titlesFor(kojo)).containsExactly("Office League invited Osu Ballers");
		assertThatThrownBy(() -> competitions.addTeam(league.id(), ballers.id(), null, null, null, sam)).hasMessage("Osu Ballers is already in this league.");
		competitions.addTeam(league.id(), null, "Greens", yaw, List.of(yaw), sam);
		assertThatThrownBy(() -> competitions.generateFixtures(league.id(), sam))
			.hasMessage("Osu Ballers hasn’t accepted yet. Wait for their captain, or drop them.");

		assertThatThrownBy(() -> competitions.answerEntry(league.id(), ballers.id(), true, ama)).hasMessage("Only Kojo can answer for Osu Ballers.");
		var accepted = competitions.answerEntry(league.id(), ballers.id(), true, kojo);
		assertThat(team(accepted, "Osu Ballers").status()).isNull();
		assertThat(team(accepted, "Osu Ballers").playerIds()).containsExactly(kojo, ama);
		assertThat(titlesFor(sam)).contains("Osu Ballers is in Office League");
		assertThat(teams.get(ballers.id(), kojo).orElseThrow().leagues()).singleElement()
			.satisfies(l -> assertThat(l.name()).isEqualTo("Office League"));

		var drawn = competitions.generateFixtures(league.id(), sam);
		teams.joinWithToken(ballers.id(), ballers.joinToken(), yaw);
		assertThat(team(competitions.get(drawn.id(), sam).orElseThrow(), "Osu Ballers").playerIds())
			.as("Yaw already plays for Greens here").doesNotContain(yaw);
		var newcomer = user("+233244555128", "Kofi Asare");
		teams.joinWithToken(ballers.id(), ballers.joinToken(), newcomer);
		assertThat(team(competitions.get(drawn.id(), sam).orElseThrow(), "Osu Ballers").playerIds())
			.as("joining the team puts them in its league squad").contains(newcomer);

		assertThatThrownBy(() -> teams.delete(ballers.id(), kojo))
			.hasMessage("Osu Ballers is in Office League. A team can’t be deleted once its league has started.");
	}

	@Test
	void aCaptainCanTurnALeagueDownAndDeleteATeamNotInOne() {
		var ballers = teams.create("Osu Ballers", null, List.of(), kojo);
		var league = competitions.create(new CompetitionDetails("Office League", "football", "5-a-side", "unlisted", null, "Legon Park",
				"Legon", null, Instant.parse("2030-06-01T09:00:00Z"), 60), sam);
		competitions.addTeam(league.id(), ballers.id(), null, null, null, sam);
		assertThat(competitions.answerEntry(league.id(), ballers.id(), false, kojo).teams()).isEmpty();
		assertThat(titlesFor(sam)).containsExactly("Osu Ballers won’t play in Office League");
		assertThatThrownBy(() -> competitions.answerEntry(league.id(), ballers.id(), true, kojo))
			.hasMessage("That invitation has already been answered.");

		assertThatThrownBy(() -> teams.delete(ballers.id(), ama)).hasMessage("Only Kojo can change the team.");
		teams.delete(ballers.id(), kojo);
		assertThat(teams.get(ballers.id(), kojo)).isEmpty();
	}

	private static CompetitionResponse.TeamView team(CompetitionResponse league, String name) {
		return league.teams().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
	}

}
