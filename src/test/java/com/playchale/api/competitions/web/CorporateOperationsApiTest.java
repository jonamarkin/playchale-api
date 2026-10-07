package com.playchale.api.competitions.web;

import com.playchale.api.TestSignIn;
import com.playchale.api.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CorporateOperationsApiTest {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper json;

	@BeforeEach
	void emptyTables() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, competitions, games, teams CASCADE").update();
	}

	@Test
	void operatorRunsACompanyLeagueWithoutPublishingDrafts() throws Exception {
		var operator = TestSignIn.as(mvc, "024 455 5199");
		var workspace = json.readTree(mvc.perform(post("/organisations").cookie(operator).contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"Overlap Corporate Sports","slug":"overlap-corporate-sports","country":"GH","primaryColour":"#0c3a3a"}
				""")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		var organisationId = workspace.at("/organisation/id").asString();
		mvc.perform(patch("/organisations/" + organisationId).cookie(operator).contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"Overlap Corporate Sports","primaryColour":"#0c3a3a","corporateEnabled":true}
				""")).andExpect(status().isOk());

		var competition = json.readTree(mvc.perform(post("/competitions").cookie(operator).contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"Company Champions League","sport":"football","format":"5-a-side",
				 "venue":{"kind":"unlisted","name":"Penalty Spot Park"},"startsAt":"2030-06-01T09:00:00Z",
				 "durationMinutes":60,"organisationId":"%s"}
				""".formatted(organisationId))).andExpect(status().isCreated())
			.andExpect(jsonPath("$.scheduleStatus").value("none"))
			.andExpect(jsonPath("$.brand.name").value("Overlap Corporate Sports"))
			.andReturn().getResponse().getContentAsString());
		var competitionId = competition.get("id").asString();

		for (var name : new String[] {"Apex Ltd", "Birim Bank", "Coast Telecom"}) {
			mvc.perform(post("/competitions/" + competitionId + "/teams").cookie(operator).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"%s\",\"playerIds\":[]}".formatted(name))).andExpect(status().isOk());
		}

		var fixtures = json.readTree(mvc.perform(post("/competitions/" + competitionId + "/operations/schedule/generate").cookie(operator))
			.andExpect(status().isOk()).andExpect(jsonPath("$[0].locationName").value("Penalty Spot Park"))
			.andReturn().getResponse().getContentAsString());
		assertThat(fixtures.size()).isEqualTo(3);
		assertThat(jdbc.sql("SELECT count(*) FROM games WHERE competition_id=:id AND visibility='private'")
			.param("id", java.util.UUID.fromString(competitionId)).query(Integer.class).single()).isEqualTo(3);

		mvc.perform(get("/competitions/" + competitionId + "/operations/schedule/validation").cookie(operator))
			.andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
		mvc.perform(post("/competitions/" + competitionId + "/operations/schedule/publish").cookie(operator))
			.andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
		mvc.perform(get("/competitions/" + competitionId))
			.andExpect(status().isOk()).andExpect(jsonPath("$.scheduleStatus").value("published"))
			.andExpect(jsonPath("$.brand.primaryColour").value("#0c3a3a"))
			.andExpect(jsonPath("$.permissions").isEmpty())
			.andExpect(jsonPath("$.finance").doesNotExist())
			.andExpect(jsonPath("$.audit").doesNotExist());
	}
}
