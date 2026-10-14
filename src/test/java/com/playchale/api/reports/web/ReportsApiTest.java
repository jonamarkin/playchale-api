package com.playchale.api.reports.web;

import java.util.Map;
import java.util.UUID;

import com.playchale.api.TestSignIn;
import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.users.api.Terms;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reporting a game, a message, a person or a venue to PlayChale: an account is needed, the thing
 * has to be there, tapping twice is one report, and nobody can bury the desk.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReportsApiTest {

	private static final String GAME = """
			{"sport":"football","format":"5-a-side","title":"Free iPhones here","startsAt":"2030-06-02T16:00:00.000Z","durationMinutes":60,
			 "venue":{"kind":"unlisted","name":"Legon Park","area":"Legon"},"capacity":10,"totalCost":0,"visibility":"public"}
			""";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper json;

	Cookie host;

	Cookie ama;

	String gameId;

	@BeforeEach
	void setUp() throws Exception {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, games, game_participants, game_messages, notifications, rate_limits, reports, host_keys CASCADE")
			.update();
		host = TestSignIn.as(mvc, "024 455 5301");
		ama = TestSignIn.as(mvc, "024 455 5302");
		gameId = json.readTree(send(host, "/games", GAME).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
			.get("id").asString();
	}

	@Test
	void aPlayerReportsAGameAndTappingAgainIsTheSameReport() throws Exception {
		report(ama, "game", gameId, "spam", "It’s selling phones").andExpect(status().isNoContent());
		report(ama, "game", gameId, "unsafe", null).andExpect(status().isNoContent());
		assertThat(jdbc.sql("SELECT reason || ':' || note FROM reports").query(String.class).list())
			.as("one report, brought up to date, keeping what they wrote").containsExactly("unsafe:It’s selling phones");
	}

	@Test
	void aMessageAPersonAndAVenueCanBeReportedToo() throws Exception {
		var conversation = json.readTree(send(host, "/games/" + gameId + "/messages", "{\"body\":\"Call me for cheap jerseys\"}")
			.andReturn().getResponse().getContentAsString());
		var message = conversation.get(conversation.size() - 1).get("id").asString();
		var hostId = me(host);
		report(ama, "message", message, "spam", null).andExpect(status().isNoContent());
		report(ama, "user", hostId, "abuse", "Keeps messaging me").andExpect(status().isNoContent());
		assertThat(jdbc.sql("SELECT count(*) FROM reports").query(Integer.class).single()).isEqualTo(2);
	}

	@Test
	void nothingIsReportedWithoutAnAccountOrAboutNothing() throws Exception {
		report(null, "game", gameId, "spam", null).andExpect(status().isUnauthorized());
		var guest = guestHost();
		report(guest, "game", gameId, "spam", null).andExpect(status().isUnauthorized());

		report(ama, "game", UUID.randomUUID().toString(), "spam", null).andExpect(status().isNotFound());
		report(ama, "planet", gameId, "spam", null).andExpect(status().isUnprocessableEntity());
		report(ama, "game", gameId, "boring", null).andExpect(status().isUnprocessableEntity());
		report(ama, "user", me(ama), "abuse", null).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.message").value("That’s you."));
		report(ama, "game", gameId, "other", "x".repeat(501)).andExpect(status().isUnprocessableEntity());
		assertThat(jdbc.sql("SELECT count(*) FROM reports").query(Integer.class).single()).isZero();
	}

	@Test
	void oneAccountCantBuryTheDesk() throws Exception {
		for (int i = 0; i < 10; i++) {
			report(ama, "game", gameId, "spam", null).andExpect(status().isNoContent());
		}
		report(ama, "game", gameId, "spam", null).andExpect(status().isConflict());
	}

	private ResultActions report(Cookie who, String type, String id, String reason, String note) throws Exception {
		var body = json.writeValueAsString(Map.of("subjectType", type, "subjectId", id, "reason", reason, "note", note == null ? "" : note));
		return send(who, "/reports", body);
	}

	private ResultActions send(Cookie who, String path, String body) throws Exception {
		var request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
		if (who != null) {
			request.cookie(who);
		}
		return mvc.perform(request);
	}

	private String me(Cookie who) throws Exception {
		return json.readTree(mvc.perform(get("/auth/session").cookie(who)).andReturn().getResponse().getContentAsString()).get("id").asString();
	}

	private Cookie guestHost() throws Exception {
		var started = mvc.perform(post("/auth/guest-hosts").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Esi\",\"phone\":\"024 455 5303\",\"country\":\"GH\",\"termsVersion\":\"%s\"}".formatted(Terms.CURRENT)))
			.andExpect(status().isCreated()).andReturn().getResponse();
		return new Cookie("playchale_session", started.getHeader("Set-Cookie").split(";")[0].split("=", 2)[1]);
	}

}
