package com.playchale.api.competitions.web;

import com.playchale.api.TestSignIn;
import com.playchale.api.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Matchday: the official's sheet, and what happens to the league when they send it.
 *
 * This is the part used on a pitch, on a phone, by someone who doesn't work for the operator — so
 * it is also the part where a retry on bad signal must not score the game twice, where a manager
 * must not be able to improve their own result, and where a correction has to leave a trail.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CorporateMatchdayTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper json;

	private Cookie operator;

	/** Referees this fixture, and nothing else in the competition. */
	private Cookie official;

	/** Runs one of the two companies. */
	private Cookie teamManager;

	private String competitionId;

	private String fixtureId;

	private String homeTeamId;

	private String homePlayerId;

	private String awayPlayerId;

	@BeforeEach
	void setUp() throws Exception {
		jdbc.sql("""
				TRUNCATE users, sign_in_codes, sessions, competitions, games, teams, organisations,
				         organisation_memberships, competition_staff, competition_entry_managers, roster_members,
				         fixture_officials, fixture_match_sheets, audit_events CASCADE
				""").update();
		operator = TestSignIn.as(mvc, "024 455 5201");
		official = TestSignIn.as(mvc, "024 455 5202");
		teamManager = TestSignIn.as(mvc, "024 455 5203");

		var workspace = body(mvc.perform(post("/organisations").cookie(operator).contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"Accra Corporate Games","slug":"accra-matchday","country":"GH","primaryColour":"#0c3a3a"}
				""")).andExpect(status().isCreated()));
		var organisationId = workspace.at("/organisation/id").asString();
		mvc.perform(patch("/organisations/" + organisationId).cookie(operator).contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"Accra Corporate Games","primaryColour":"#0c3a3a","corporateEnabled":true}
				""")).andExpect(status().isOk());

		var competition = body(mvc.perform(post("/competitions").cookie(operator).contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"Matchday League","sport":"football","format":"5-a-side",
				 "venue":{"kind":"unlisted","name":"Accra Sports Park"},"startsAt":"2030-06-01T09:00:00Z",
				 "durationMinutes":60,"playerLists":"optional","organisationId":"%s"}
				""".formatted(organisationId))).andExpect(status().isCreated()));
		competitionId = competition.get("id").asString();

		JsonNode withTeams = null;
		for (var name : new String[] { "Apex Ltd", "Birim Bank", "Coast Telecom" }) {
			withTeams = body(mvc.perform(post("/competitions/" + competitionId + "/teams").cookie(operator)
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"%s\",\"playerIds\":[]}".formatted(name)))
				.andExpect(status().isOk()));
		}
		homeTeamId = withTeams.at("/teams/0/id").asString();
		var awayTeamId = withTeams.at("/teams/1/id").asString();
		homePlayerId = approvedPlayer(homeTeamId, "Kofi Asare");
		awayPlayerId = approvedPlayer(awayTeamId, "Samuel Adjei");

		var schedule = body(mvc.perform(post("/competitions/" + competitionId + "/operations/schedule/generate").cookie(operator))
			.andExpect(status().isOk()));
		fixtureId = fixtureOf(schedule, homeTeamId, awayTeamId);
		mvc.perform(put("/competitions/" + competitionId + "/operations/fixtures/" + fixtureId + "/official").cookie(operator)
			.contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"%s\"}".formatted(userId(official))))
			.andExpect(status().isOk());

		// A result can only go in once the game has kicked off, and the draw puts fixtures in the future.
		jdbc.sql("UPDATE games SET starts_at = :when WHERE id = :game")
			.param("when", java.time.OffsetDateTime.now().minusHours(2)).param("game", UUID.fromString(fixtureId))
			.update();

		var invite = body(mvc.perform(post("/competitions/" + competitionId + "/operations/invitations").cookie(operator)
			.contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"team-manager\",\"teamId\":\"%s\"}".formatted(homeTeamId)))
			.andExpect(status().isOk()));
		mvc.perform(post("/competition-invitations/accept").cookie(teamManager).contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"%s\"}".formatted(tokenOf(invite)))).andExpect(status().isOk());
	}

	@Test
	void theAssignedOfficialSendsTheResultAndTheTableFollows() throws Exception {
		mvc.perform(post(submit()).cookie(official).contentType(MediaType.APPLICATION_JSON).content(sheet(3, 1)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("submitted"));

		// The table, the stats and the fixture all move on one submission.
		var page = body(mvc.perform(get("/competitions/" + competitionId)).andExpect(status().isOk()));
		var top = page.at("/table/0");
		assertThat(top.at("/team/id").asString()).isEqualTo(homeTeamId);
		assertThat(top.get("points").asInt()).isEqualTo(3);
		assertThat(top.get("scored").asInt()).isEqualTo(3);
		assertThat(page.at("/fixtures").valueStream().filter(f -> f.get("id").asString().equals(fixtureId))
			.findFirst().orElseThrow().get("status").asString()).isEqualTo("completed");
	}

	@Test
	void sendingTheSameSheetTwiceDoesNotScoreTheGameTwice() throws Exception {
		mvc.perform(post(submit()).cookie(official).contentType(MediaType.APPLICATION_JSON).content(sheet(3, 1)))
			.andExpect(status().isOk());
		// The referee's phone drops the reply and tries again: the same result, not a second one.
		mvc.perform(post(submit()).cookie(official).contentType(MediaType.APPLICATION_JSON).content(sheet(9, 9)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.homeScore").value(3))
			.andExpect(jsonPath("$.awayScore").value(1));

		assertThat(jdbc.sql("SELECT count(*) FROM game_results WHERE game_id = :game")
			.param("game", UUID.fromString(fixtureId)).query(Integer.class).single()).isEqualTo(1);
		assertThat(body(mvc.perform(get("/competitions/" + competitionId))).at("/table/0/played").asInt()).isEqualTo(1);
	}

	@Test
	void aSubmittedResultIsNotTheTeamsToChange() throws Exception {
		mvc.perform(post(submit()).cookie(official).contentType(MediaType.APPLICATION_JSON).content(sheet(3, 1)))
			.andExpect(status().isOk());

		// A company's own manager has no business on the sheet at all, before or after.
		mvc.perform(get(matchSheet()).cookie(teamManager)).andExpect(status().isNotFound());
		mvc.perform(put(matchSheet()).cookie(teamManager).contentType(MediaType.APPLICATION_JSON).content(sheet(5, 0)))
			.andExpect(status().isNotFound());

		// And the official who sent it can't quietly edit it either.
		mvc.perform(put(matchSheet()).cookie(official).contentType(MediaType.APPLICATION_JSON).content(sheet(5, 0)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.message").value("A submitted result can only be changed through a correction with a reason."));
	}

	@Test
	void correctingKeepsWhatItSaidBeforeAndWhy() throws Exception {
		mvc.perform(post(submit()).cookie(official).contentType(MediaType.APPLICATION_JSON).content(sheet(3, 1)))
			.andExpect(status().isOk());

		// A correction without a reason is not a correction.
		mvc.perform(post(correct()).cookie(operator).contentType(MediaType.APPLICATION_JSON)
			.content("{\"sheet\":%s,\"reason\":\"  \"}".formatted(sheet(2, 1)))).andExpect(status().isUnprocessableEntity());

		mvc.perform(post(correct()).cookie(operator).contentType(MediaType.APPLICATION_JSON)
			.content("{\"sheet\":%s,\"reason\":\"Third goal was offside; both captains agreed.\"}".formatted(sheet(2, 1))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.homeScore").value(2));

		// What it said before is kept, with the reason, so a disputed score can be traced back.
		var revision = jdbc.sql("SELECT reason, snapshot::text FROM match_sheet_revisions WHERE game_id = :game")
			.param("game", UUID.fromString(fixtureId)).query((rs, n) -> new String[] { rs.getString(1), rs.getString(2) }).single();
		assertThat(revision[0]).isEqualTo("Third goal was offside; both captains agreed.");
		assertThat(json.readTree(revision[1]).get("homeScore").asInt()).as("the score as it stood before").isEqualTo(3);
		assertThat(body(mvc.perform(get("/competitions/" + competitionId))).at("/table/0/scored").asInt()).isEqualTo(2);

		// An official may referee, but correcting the record is the operator's call.
		mvc.perform(post(correct()).cookie(official).contentType(MediaType.APPLICATION_JSON)
			.content("{\"sheet\":%s,\"reason\":\"Changed my mind.\"}".formatted(sheet(4, 0)))).andExpect(status().isNotFound());
	}

	@Test
	void onlyApprovedPlayersOfTheTwoTeamsReachTheSheet() throws Exception {
		var otherTeam = body(mvc.perform(get("/competitions/" + competitionId))).at("/teams/2/id").asString();
		var outsider = approvedPlayer(otherTeam, "Prince Amoah");

		mvc.perform(put(matchSheet()).cookie(official).contentType(MediaType.APPLICATION_JSON).content("""
				{"homeScore":1,"awayScore":0,"players":[{"rosterMemberId":"%s","teamId":"%s","participation":"starter","checkedIn":true,"goals":1,"assists":0}],"cards":[]}
				""".formatted(outsider, otherTeam)))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.message").value("Only approved roster members can appear on the match sheet."));
	}

	/* ------------------------------------------------------------------ helpers */

	private String submit() {
		return "/competitions/" + competitionId + "/operations/fixtures/" + fixtureId + "/match-sheet/submit";
	}

	private String correct() {
		return "/competitions/" + competitionId + "/operations/fixtures/" + fixtureId + "/match-sheet/correct";
	}

	private String matchSheet() {
		return "/competitions/" + competitionId + "/operations/fixtures/" + fixtureId + "/match-sheet";
	}

	/** A sheet with one player a side: the home scorer carries the goals. */
	private String sheet(int home, int away) {
		return """
				{"homeScore":%d,"awayScore":%d,"notes":"Played in full.","players":[
				 {"rosterMemberId":"%s","teamId":"%s","participation":"starter","checkedIn":true,"goals":%d,"assists":0},
				 {"rosterMemberId":"%s","teamId":"%s","participation":"starter","checkedIn":true,"goals":%d,"assists":0}],
				 "cards":[{"rosterMemberId":"%s","colour":"yellow","minute":57,"note":"Dissent"}]}
				""".formatted(home, away, homePlayerId, homeTeamId, home, awayPlayerId, awayTeamOf(), away, homePlayerId);
	}

	private String awayTeamOf() {
		return body(() -> mvc.perform(get("/competitions/" + competitionId))).at("/teams/1/id").asString();
	}

	/** Adds a player, attests and approves them in one go: eligibility has its own test. */
	private String approvedPlayer(String teamId, String name) throws Exception {
		var added = body(mvc.perform(post("/competitions/" + competitionId + "/operations/teams/" + teamId + "/roster").cookie(operator)
			.contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"%s\"}".formatted(name)))
			.andExpect(status().isCreated()));
		var memberId = added.get("id").asString();
		mvc.perform(post("/competitions/" + competitionId + "/operations/teams/" + teamId + "/roster/submit").cookie(operator)
			.contentType(MediaType.APPLICATION_JSON).content("{\"attest\":true}")).andExpect(status().isOk());
		mvc.perform(post("/competitions/" + competitionId + "/operations/teams/" + teamId + "/roster/review").cookie(operator)
			.contentType(MediaType.APPLICATION_JSON).content("{\"memberIds\":[\"%s\"],\"decision\":\"approved\"}".formatted(memberId)))
			.andExpect(status().isOk());
		return memberId;
	}

	/** The fixture these two teams meet in, whichever round the draw put it in. */
	private static String fixtureOf(JsonNode schedule, String home, String away) {
		return schedule.valueStream()
			.filter(f -> (f.get("homeTeamId").asString().equals(home) && f.get("awayTeamId").asString().equals(away))
					|| (f.get("homeTeamId").asString().equals(away) && f.get("awayTeamId").asString().equals(home)))
			.findFirst().orElseThrow().get("id").asString();
	}

	private String userId(Cookie who) throws Exception {
		return body(mvc.perform(get("/auth/session").cookie(who))).get("id").asString();
	}

	private String tokenOf(JsonNode invitation) {
		var url = invitation.get("inviteUrl").asString();
		return url.substring(url.indexOf("token=") + "token=".length());
	}

	private JsonNode body(ResultActions actions) throws Exception {
		return json.readTree(actions.andReturn().getResponse().getContentAsString());
	}

	private JsonNode body(ThrowingActions actions) {
		try {
			return body(actions.get());
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	@FunctionalInterface
	private interface ThrowingActions {

		ResultActions get() throws Exception;

	}

}
