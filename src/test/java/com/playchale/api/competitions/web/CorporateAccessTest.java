package com.playchale.api.competitions.web;

import com.playchale.api.TestSignIn;
import com.playchale.api.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may do what in a company league, checked at the API rather than trusted to the screens. A
 * company's roster, what it was charged and why a player was turned down are the things an operator
 * would be embarrassed to leak, so each is asked for by someone who shouldn't have it.
 *
 * <p>Refusals are deliberately "not found" rather than "forbidden": someone guessing at other
 * companies' ids learns nothing from the difference.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CorporateAccessTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper json;

	/** Runs the workspace and the competition. */
	private Cookie operator;

	/** Runs one company's squad, and nothing else. */
	private Cookie teamManager;

	/** Signed in, with no part in any of this. */
	private Cookie outsider;

	private String competitionId;

	private String organisationId;

	private String teamId;

	private String otherTeamId;

	@BeforeEach
	void setUp() throws Exception {
		jdbc.sql("""
				TRUNCATE users, sign_in_codes, sessions, competitions, games, teams, organisations,
				         organisation_memberships, competition_staff, competition_entry_managers, roster_members,
				         competition_entry_finance, audit_events CASCADE
				""").update();
		operator = TestSignIn.as(mvc, "024 455 5101");
		teamManager = TestSignIn.as(mvc, "024 455 5102");
		outsider = TestSignIn.as(mvc, "024 455 5103");

		var workspace = body(mvc.perform(post("/organisations").cookie(operator).contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"Accra Corporate Games","slug":"accra-corporate-games","country":"GH","primaryColour":"#0c3a3a"}
				""")).andExpect(status().isCreated()));
		organisationId = workspace.at("/organisation/id").asString();
		mvc.perform(patch("/organisations/" + organisationId).cookie(operator).contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"Accra Corporate Games","primaryColour":"#0c3a3a","corporateEnabled":true}
				""")).andExpect(status().isOk());

		var competition = body(mvc.perform(post("/competitions").cookie(operator).contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"Company Champions League","sport":"football","format":"5-a-side",
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
		teamId = withTeams.at("/teams/0/id").asString();
		otherTeamId = withTeams.at("/teams/1/id").asString();

		// The company's own manager joins by invitation, as a real one would.
		var invite = body(mvc.perform(post("/competitions/" + competitionId + "/operations/invitations").cookie(operator)
			.contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"team-manager\",\"teamId\":\"%s\"}".formatted(teamId)))
			.andExpect(status().isOk()));
		mvc.perform(post("/competition-invitations/accept").cookie(teamManager).contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"%s\"}".formatted(tokenOf(invite)))).andExpect(status().isOk());
	}

	@Test
	void anOutsiderCannotSeeAnythingAboutSomeoneElsesLeague() throws Exception {
		for (var path : new String[] { "", "/finance", "/announcements", "/audit", "/schedule", "/locations" }) {
			mvc.perform(get("/competitions/" + competitionId + "/operations" + path).cookie(outsider))
				.andExpect(status().isNotFound());
		}
		mvc.perform(get("/competitions/" + competitionId + "/operations/teams/" + teamId + "/roster").cookie(outsider))
			.andExpect(status().isNotFound());
		mvc.perform(get("/organisations/" + organisationId).cookie(outsider)).andExpect(status().isNotFound());
	}

	@Test
	void aTeamManagerRunsTheirOwnSquadAndNothingElse() throws Exception {
		mvc.perform(post("/competitions/" + competitionId + "/operations/teams/" + teamId + "/roster").cookie(teamManager)
			.contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Kofi Asare\",\"employeeReference\":\"APX-001\"}"))
			.andExpect(status().isCreated());

		// Another company's roster is not theirs to read or to add to.
		mvc.perform(get("/competitions/" + competitionId + "/operations/teams/" + otherTeamId + "/roster").cookie(teamManager))
			.andExpect(status().isNotFound());
		mvc.perform(post("/competitions/" + competitionId + "/operations/teams/" + otherTeamId + "/roster").cookie(teamManager)
			.contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Ringer\"}"))
			.andExpect(status().isNotFound());

		// Nor is anything that belongs to whoever runs the whole competition.
		mvc.perform(get("/competitions/" + competitionId + "/operations/finance").cookie(teamManager)).andExpect(status().isNotFound());
		mvc.perform(get("/competitions/" + competitionId + "/operations/audit").cookie(teamManager)).andExpect(status().isNotFound());
		mvc.perform(post("/competitions/" + competitionId + "/operations/schedule/generate").cookie(teamManager))
			.andExpect(status().isNotFound());
		mvc.perform(post("/competitions/" + competitionId + "/operations/announcements").cookie(teamManager)
			.contentType(MediaType.APPLICATION_JSON).content("{\"audience\":\"everyone\",\"title\":\"Hi\",\"body\":\"All\",\"acknowledgement\":false}"))
			.andExpect(status().isNotFound());
	}

	@Test
	void aManagerCannotApproveTheirOwnAttestation() throws Exception {
		var added = body(mvc.perform(post("/competitions/" + competitionId + "/operations/teams/" + teamId + "/roster").cookie(teamManager)
			.contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Kofi Asare\",\"employeeReference\":\"APX-001\"}"))
			.andExpect(status().isCreated()));
		var memberId = added.get("id").asString();

		mvc.perform(post("/competitions/" + competitionId + "/operations/teams/" + teamId + "/roster/submit").cookie(teamManager)
			.contentType(MediaType.APPLICATION_JSON).content("{\"attest\":true}")).andExpect(status().isOk());

		// Attesting is the manager's; deciding is the organiser's. One person doing both is the whole
		// point of having two steps.
		mvc.perform(post("/competitions/" + competitionId + "/operations/teams/" + teamId + "/roster/review").cookie(teamManager)
			.contentType(MediaType.APPLICATION_JSON).content("{\"memberIds\":[\"%s\"],\"decision\":\"approved\"}".formatted(memberId)))
			.andExpect(status().isNotFound());

		mvc.perform(post("/competitions/" + competitionId + "/operations/teams/" + teamId + "/roster/review").cookie(operator)
			.contentType(MediaType.APPLICATION_JSON).content("{\"memberIds\":[\"%s\"],\"decision\":\"approved\"}".formatted(memberId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].eligibilityState").value("approved"));
	}

	@Test
	void theWorkspaceItselfIsOnlyTheOwnersToChange() throws Exception {
		mvc.perform(patch("/organisations/" + organisationId).cookie(outsider).contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"Hijacked","primaryColour":"#000000"}
				""")).andExpect(status().isNotFound());
		mvc.perform(post("/organisations/" + organisationId + "/invitations").cookie(outsider)).andExpect(status().isNotFound());
	}

	@Test
	void aCompetitionCannotBeStartedInSomeoneElsesWorkspace() throws Exception {
		mvc.perform(post("/competitions").cookie(outsider).contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"Piggyback League","sport":"football","format":"5-a-side",
				 "venue":{"kind":"unlisted","name":"Accra Sports Park"},"startsAt":"2030-06-01T09:00:00Z",
				 "durationMinutes":60,"organisationId":"%s"}
				""".formatted(organisationId))).andExpect(status().isNotFound());
	}

	@Test
	void thePublicPageCarriesNoneOfTheOperationsBehindIt() throws Exception {
		mvc.perform(post("/competitions/" + competitionId + "/operations/teams/" + teamId + "/roster").cookie(operator)
			.contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Kofi Asare\",\"employeeReference\":\"APX-001\"}"))
			.andExpect(status().isCreated());
		mvc.perform(put("/competitions/" + competitionId + "/operations/finance/" + teamId).cookie(operator)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amountDue\":50000,\"status\":\"paid\",\"method\":\"momo\",\"reference\":\"MM-1\",\"privateNote\":\"Paid at the office\"}"))
			.andExpect(status().isOk());

		// Signed out, with the link: the brand and the fixtures, and nothing a company would mind.
		var page = mvc.perform(get("/competitions/" + competitionId)).andExpect(status().isOk())
			.andExpect(jsonPath("$.brand.name").value("Accra Corporate Games"))
			.andExpect(jsonPath("$.finance").doesNotExist())
			.andExpect(jsonPath("$.audit").doesNotExist())
			.andExpect(jsonPath("$.rosters").doesNotExist())
			.andReturn().getResponse().getContentAsString();
		org.assertj.core.api.Assertions.assertThat(page)
			.doesNotContain("APX-001")
			.doesNotContain("Paid at the office")
			.doesNotContain("MM-1");
	}

	private JsonNode body(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
		return json.readTree(actions.andReturn().getResponse().getContentAsString());
	}

	/** The invitation link carries the token; the API only ever stores its hash. */
	private String tokenOf(JsonNode invitation) {
		var url = invitation.get("inviteUrl").asString();
		return url.substring(url.indexOf("token=") + "token=".length());
	}

}
