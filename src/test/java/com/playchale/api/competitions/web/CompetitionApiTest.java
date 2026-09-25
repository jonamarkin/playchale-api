package com.playchale.api.competitions.web;

import java.util.Set;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A league over HTTP, with the JSON the web app's competition pages expect. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CompetitionApiTest {

	/** The web app's CompetitionView. */
	private static final Set<String> VIEW_FIELDS = Set.of("id", "name", "sport", "format", "organiserId", "venue", "startsAt",
			"durationMinutes", "status", "points", "createdAt", "organiser", "teams", "table", "fixtures", "rounds", "requests");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper json;

	@BeforeEach
	void emptyTables() {
		jdbc.sql("""
				TRUNCATE users, sign_in_codes, sessions, games, game_participants, notifications, game_results,
				         competitions, teams, team_players, join_requests CASCADE
				""").update();
	}

	@Test
	void settingUpALeague() throws Exception {
		var sam = TestSignIn.as(mvc, "024 455 5120");
		var created = json.readTree(mvc.perform(post("/competitions").cookie(sam).contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"Office League","sport":"football","format":"5-a-side","venue":{"kind":"unlisted","name":"Legon Park"},
				 "startsAt":"2030-06-01T09:00:00.000Z","durationMinutes":60}
				""")).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("draft"))
			.andExpect(jsonPath("$.points.win").value(3)).andReturn().getResponse().getContentAsString());
		assertThat(VIEW_FIELDS).containsAll(created.propertyNames());
		var id = created.get("id").asString();

		for (var team : new String[] { "Reds", "Blues", "Greens" }) {
			mvc.perform(post("/competitions/" + id + "/teams").cookie(sam).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"%s\",\"playerIds\":[]}".formatted(team)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.teams[-1].playerIds").isEmpty());
		}
		mvc.perform(post("/competitions/" + id + "/fixtures").cookie(sam))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("running"))
			.andExpect(jsonPath("$.fixtures.length()").value(3))
			.andExpect(jsonPath("$.fixtures[0].fixture.round").value(1))
			.andExpect(jsonPath("$.fixtures[0].fixtureTeams.home.joinToken").value(""))
			.andExpect(jsonPath("$.table.length()").value(3))
			.andExpect(jsonPath("$.table[0].team.name").exists());

		mvc.perform(get("/competitions")).andExpect(jsonPath("$[0].id").value(id)).andExpect(jsonPath("$[0].teams[0].joinToken").value(""));
		mvc.perform(get("/competitions/" + id).cookie(sam)).andExpect(jsonPath("$.teams[0].joinToken").isNotEmpty());
		mvc.perform(get("/competitions/0199f000-0000-7000-8000-000000000000")).andExpect(status().isNotFound());
	}

}
