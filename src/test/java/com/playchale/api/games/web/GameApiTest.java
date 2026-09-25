package com.playchale.api.games.web;

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

/** Games over HTTP, with the JSON the web app sends and expects. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GameApiTest {

	/** The web app's GameView (Game plus host, players, share, spotsLeft). Optional fields may be absent. */
	private static final Set<String> GAME_VIEW_FIELDS = Set.of("id", "sport", "format", "title", "startsAt", "durationMinutes",
			"venue", "capacity", "totalCost", "currency", "visibility", "hostId", "notes", "participants", "status", "result",
			"fixture", "createdAt", "cancelledAt", "cancelReason", "host", "players", "fixtureTeams", "share", "spotsLeft");

	private static final String NEW_GAME = """
			{"sport":"football","format":"5-a-side","title":"Sunday 5s","startsAt":"2030-06-02T16:00:00.000Z","durationMinutes":60,
			 "venue":{"kind":"unlisted","name":"Legon Park","area":"Legon"},"capacity":10,"totalCost":25000,"visibility":"public"}
			""";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper json;

	@BeforeEach
	void emptyTables() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, venues, pitches, bookings, games, game_participants, notifications CASCADE").update();
	}

	@Test
	void hostingAndJoiningAGame() throws Exception {
		var kwame = TestSignIn.as(mvc, "024 455 5123");
		mvc.perform(patch("/me").cookie(kwame).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Kwame Mensah\"}"));
		var created = mvc.perform(post("/games").cookie(kwame).contentType(MediaType.APPLICATION_JSON).content(NEW_GAME))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.share").value(2500))
			.andExpect(jsonPath("$.spotsLeft").value(9))
			.andExpect(jsonPath("$.currency").value("GHS"))
			.andExpect(jsonPath("$.venue.kind").value("unlisted"))
			.andExpect(jsonPath("$.host.name").value("Kwame Mensah"))
			.andReturn().getResponse().getContentAsString();
		var game = json.readTree(created);
		assertThat(GAME_VIEW_FIELDS).containsAll(game.propertyNames());
		var id = game.get("id").asString();

		var kojo = TestSignIn.as(mvc, "024 455 5124");
		mvc.perform(post("/games/" + id + "/players").cookie(kojo))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.players.length()").value(2))
			.andExpect(jsonPath("$.players[0].phone").value(""))
			.andExpect(jsonPath("$.players[1].phone").value("+233244555124"));

		mvc.perform(get("/games")).andExpect(jsonPath("$[0].id").value(id)).andExpect(jsonPath("$[0].players[1].phone").value(""));
		mvc.perform(get("/me/games").cookie(kojo)).andExpect(jsonPath("$[0].id").value(id));

		mvc.perform(get("/notifications").cookie(kwame))
			.andExpect(jsonPath("$[0].kind").value("player-joined"))
			.andExpect(jsonPath("$[0].read").value(false))
			.andExpect(jsonPath("$[0].actor.phone").value(""));
		mvc.perform(post("/notifications/read-all").cookie(kwame)).andExpect(status().isNoContent());
		mvc.perform(get("/notifications").cookie(kwame)).andExpect(jsonPath("$[0].read").value(true));

		mvc.perform(delete("/games/" + id + "/players/me").cookie(kojo)).andExpect(jsonPath("$.players.length()").value(1));
		mvc.perform(post("/games/" + id + "/cancellation").cookie(kojo).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.message").value("Only the host can do that."));
		mvc.perform(post("/games/" + id + "/cancellation").cookie(kwame).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Rain\"}"))
			.andExpect(jsonPath("$.status").value("cancelled"))
			.andExpect(jsonPath("$.cancelReason").value("Rain"));
	}

	@Test
	void guestsAndClaimLinks() throws Exception {
		var kwame = TestSignIn.as(mvc, "024 455 5123");
		var id = json.readTree(mvc.perform(post("/games").cookie(kwame).contentType(MediaType.APPLICATION_JSON).content(NEW_GAME))
			.andReturn().getResponse().getContentAsString()).get("id").asString();

		var added = json.readTree(mvc.perform(post("/games/" + id + "/guests").cookie(kwame).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Kofi from work\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.game.players[1].id").value(org.hamcrest.Matchers.startsWith("guest:")))
			.andExpect(jsonPath("$.game.participants[1].userId").value(""))
			.andReturn().getResponse().getContentAsString());
		var token = added.get("token").asString();

		var kofi = TestSignIn.as(mvc, "020 123 4567");
		mvc.perform(post("/games/" + id + "/claims").cookie(kofi).contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"%s\"}".formatted(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.participants[1].guest").doesNotExist())
			.andExpect(jsonPath("$.participants[1].userId").isNotEmpty());
	}

	@Test
	void unknownGamesAreNotFound() throws Exception {
		mvc.perform(get("/games/0199f000-0000-7000-8000-000000000000")).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.message").value("This game no longer exists."));
		mvc.perform(get("/games/g-1")).andExpect(status().isNotFound());
	}

}
