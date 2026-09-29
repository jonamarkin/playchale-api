package com.playchale.api.teams.web;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Teams over HTTP, with the JSON the web app's team pages expect. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TeamApiTest {

	/** The web app's TeamProfile. */
	private static final Set<String> VIEW_FIELDS = Set.of("id", "name", "tint", "captainId", "captain", "memberIds", "members", "joinToken",
			"createdAt", "leagues", "requests", "requested", "record", "upcoming", "recent", "logoVersion");

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
		mvc.perform(get("/teams").cookie(esi).param("query", "osu"))
			.andExpect(jsonPath("$[0].name").value("Osu Ballers"))
			.andExpect(jsonPath("$[0].memberCount").value(1))
			.andExpect(jsonPath("$[0].joinToken").doesNotExist());
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

	/** The crest a team plays under: the captain's to set, anyone's to see, and checked before it's kept. */
	@Test
	void aTeamWearsACrest() throws Exception {
		var kwame = TestSignIn.as(mvc, "024 455 5130");
		var yaw = TestSignIn.as(mvc, "024 455 5131");
		var id = json.readTree(mvc.perform(post("/teams").cookie(kwame).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Osu Ballers\"}")).andReturn().getResponse().getContentAsString()).get("id").asString();

		// A one-pixel PNG, as the browser would send after shrinking a logo.
		var png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

		// Only the captain, and only something that really is the image it claims to be.
		mvc.perform(put("/teams/" + id + "/logo").cookie(yaw).contentType(MediaType.IMAGE_PNG).content(png))
			.andExpect(status().isConflict());
		mvc.perform(put("/teams/" + id + "/logo").cookie(kwame).contentType(MediaType.IMAGE_PNG).content("not an image".getBytes(StandardCharsets.UTF_8)))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.message").value("That file isn’t the image it claims to be."));
		mvc.perform(put("/teams/" + id + "/logo").cookie(kwame).contentType(MediaType.APPLICATION_PDF).content(png))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.message").value("A crest has to be a PNG, JPEG or WebP image."));
		mvc.perform(put("/teams/" + id + "/logo").cookie(kwame).contentType(MediaType.IMAGE_PNG).content(new byte[200 * 1024]))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.message").value("That image is too big. Pick one under 128 KB."));

		var saved = json.readTree(mvc.perform(put("/teams/" + id + "/logo").cookie(kwame).contentType(MediaType.IMAGE_PNG).content(png))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(VIEW_FIELDS).containsAll(saved.propertyNames());
		assertThat(saved.get("logoVersion").asLong()).isPositive();

		// Anyone can see it, and it's cached under a URL that changes with the crest.
		mvc.perform(get("/teams/" + id + "/logo"))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", MediaType.IMAGE_PNG_VALUE))
			.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=31536000")))
			.andExpect(content().bytes(png));

		// And the captain can take it off again.
		mvc.perform(delete("/teams/" + id + "/logo").cookie(kwame))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.logoVersion").doesNotExist());
		mvc.perform(get("/teams/" + id + "/logo")).andExpect(status().isNotFound());
	}

}
