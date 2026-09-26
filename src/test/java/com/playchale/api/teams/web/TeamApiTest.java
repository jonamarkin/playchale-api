package com.playchale.api.teams.web;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Teams over HTTP, with the JSON the web app's team pages expect. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TeamApiTest {

	/** The web app's TeamProfile. */
	private static final Set<String> VIEW_FIELDS = Set.of("id", "name", "tint", "captainId", "captain", "memberIds", "members", "joinToken",
			"createdAt", "leagues", "requests", "requested");

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
				         competitions, teams, team_members, team_join_requests, competition_entries, entry_players CASCADE
				""").update();
	}

	@Test
	void settingUpAndJoiningATeam() throws Exception {
		var kojo = TestSignIn.as(mvc, "024 455 5124");
		var esi = TestSignIn.as(mvc, "024 455 5127");
		mvc.perform(post("/teams").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Osu Ballers\"}"))
			.andExpect(status().isUnauthorized());

		var created = json.readTree(mvc.perform(post("/teams").cookie(kojo).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Osu Ballers\",\"tint\":\"#a9c4f2\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.tint").value("#a9c4f2"))
			.andExpect(jsonPath("$.memberIds.length()").value(1))
			.andExpect(jsonPath("$.requests").isEmpty())
			.andReturn().getResponse().getContentAsString());
		assertThat(VIEW_FIELDS).containsAll(created.propertyNames());
		var id = created.get("id").asString();
		var token = created.get("joinToken").asString();

		mvc.perform(get("/teams/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.joinToken").value(""));
		mvc.perform(post("/teams/" + id + "/joins").cookie(esi).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"nope\"}"))
			.andExpect(status().isNotFound());
		var joined = json.readTree(mvc.perform(post("/teams/" + id + "/joins").cookie(esi).contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"%s\"}".formatted(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.memberIds.length()").value(2))
			.andReturn().getResponse().getContentAsString());
		var esiId = joined.get("memberIds").get(1).asString();
		mvc.perform(get("/me/teams").cookie(esi)).andExpect(jsonPath("$[0].name").value("Osu Ballers"));

		mvc.perform(patch("/teams/" + id).cookie(esi).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Esi's\"}"))
			.andExpect(status().isConflict());
		mvc.perform(patch("/teams/" + id).cookie(kojo).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Osu Ballers FC\"}"))
			.andExpect(jsonPath("$.name").value("Osu Ballers FC"));

		mvc.perform(delete("/teams/" + id + "/members/" + esiId).cookie(esi)).andExpect(jsonPath("$.memberIds.length()").value(1));
		mvc.perform(delete("/teams/" + id).cookie(kojo)).andExpect(status().isNoContent());
		mvc.perform(get("/teams/" + id)).andExpect(status().isNotFound());
	}

}
