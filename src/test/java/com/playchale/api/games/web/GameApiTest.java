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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Games over HTTP, with the JSON the web app sends and expects. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GameApiTest {

	/** The web app's GameView (Game plus host, players, share, spotsLeft). Optional fields may be absent. */
	private static final Set<String> GAME_VIEW_FIELDS = Set.of("id", "sport", "format", "title", "startsAt", "durationMinutes",
			"venue", "capacity", "totalCost", "pricing", "currency", "visibility", "hostId", "notes", "participants", "status", "result",
			"fixture", "createdAt", "cancelledAt", "cancelReason", "host", "players", "fixtureTeams", "share", "spotsLeft", "invites", "friendly",
			"country", "timezone");

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
		mvc.perform(patch("/me").cookie(kojo).contentType(MediaType.APPLICATION_JSON)
			.content("{\"sports\":[\"football\"],\"roles\":{\"football\":[\"goalkeeper\"]}}"));
		mvc.perform(post("/games/" + id + "/players").cookie(kojo))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.players.length()").value(2))
			.andExpect(jsonPath("$.players[0].phone").value(""))
			.andExpect(jsonPath("$.players[0].roles").isEmpty())
			.andExpect(jsonPath("$.players[1].phone").value("+233244555124"))
			.andExpect(jsonPath("$.players[1].roles.football[0]").value("goalkeeper"));

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
	void aGameWithNoLimit() throws Exception {
		var kwame = TestSignIn.as(mvc, "024 455 5123");
		var open = NEW_GAME.replace("\"capacity\":10,\"totalCost\":25000", "\"capacity\":null,\"totalCost\":0");
		var id = json.readTree(mvc.perform(post("/games").cookie(kwame).contentType(MediaType.APPLICATION_JSON).content(open))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.capacity").doesNotExist())
			.andExpect(jsonPath("$.spotsLeft").doesNotExist())
			.andReturn().getResponse().getContentAsString()).get("id").asString();
		for (var n = 0; n < 3; n++) {
			mvc.perform(post("/games/" + id + "/players").cookie(TestSignIn.as(mvc, "024 455 51" + (30 + n)))).andExpect(status().isOk());
		}
		mvc.perform(get("/games/" + id)).andExpect(jsonPath("$.status").value("open")).andExpect(jsonPath("$.players.length()").value(4));
		mvc.perform(get("/notifications").cookie(kwame)).andExpect(jsonPath("$[0].body").value(org.hamcrest.Matchers.startsWith("4 going · ")));

		var priced = NEW_GAME.replace("\"capacity\":10,\"totalCost\":25000", "\"capacity\":null,\"totalCost\":3000,\"pricing\":\"per-player\"");
		mvc.perform(post("/games").cookie(kwame).contentType(MediaType.APPLICATION_JSON).content(priced))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.share").value(3000));
		var split = NEW_GAME.replace("\"capacity\":10", "\"capacity\":null");
		mvc.perform(post("/games").cookie(kwame).contentType(MediaType.APPLICATION_JSON).content(split))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.message").value("Splitting a cost needs a number of spots. Set the spots, or charge each player a price."));
		mvc.perform(post("/games").cookie(kwame).contentType(MediaType.APPLICATION_JSON).content(NEW_GAME.replace("\"capacity\":10", "\"capacity\":101")))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.message").value("A game can have at most 100 spots, or no limit."));
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
	void invitesAndAnswersOverHttp() throws Exception {
		var kwame = TestSignIn.as(mvc, "024 455 5123");
		var esi = TestSignIn.as(mvc, "024 455 5127");
		var id = json.readTree(mvc.perform(post("/games").cookie(kwame).contentType(MediaType.APPLICATION_JSON).content(NEW_GAME))
			.andReturn().getResponse().getContentAsString()).get("id").asString();
		var team = json.readTree(mvc.perform(post("/teams").cookie(esi).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Labone United\"}"))
			.andReturn().getResponse().getContentAsString()).get("id").asString();
		mvc.perform(post("/games/" + id + "/team-invites").cookie(kwame).contentType(MediaType.APPLICATION_JSON).content("{\"teamId\":\"%s\"}".formatted(team)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.message").value("You can only invite a team you’re in."));

		var esiId = json.readTree(mvc.perform(get("/me/teams").cookie(esi)).andReturn().getResponse().getContentAsString()).get(0).get("captainId").asString();
		mvc.perform(post("/games/" + id + "/invites").cookie(kwame).contentType(MediaType.APPLICATION_JSON).content("{\"userIds\":[\"%s\"]}".formatted(esiId)))
			.andExpect(jsonPath("$.invited").value(1));
		mvc.perform(get("/me/invites").cookie(esi)).andExpect(jsonPath("$[0].id").value(id))
			.andExpect(jsonPath("$[0].invites.length()").value(1))
			.andExpect(jsonPath("$[0].invites[0].status").value("pending"))
			.andExpect(jsonPath("$[0].invites[0].user.phone").value("+233244555127"));
		mvc.perform(get("/games/" + id)).andExpect(jsonPath("$.invites").doesNotExist());

		mvc.perform(post("/games/" + id + "/invite-answers").cookie(esi).contentType(MediaType.APPLICATION_JSON).content("{\"accept\":false}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.invites[0].status").value("declined"));
		mvc.perform(get("/games/" + id).cookie(kwame))
			.andExpect(jsonPath("$.invites[0].status").value("declined"))
			.andExpect(jsonPath("$.invites[0].user.phone").value("")); // someone else's number is never shown
		mvc.perform(get("/me/invites").cookie(esi)).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void unknownGamesAreNotFound() throws Exception {
		mvc.perform(get("/games/0199f000-0000-7000-8000-000000000000")).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.message").value("This game no longer exists."));
		mvc.perform(get("/games/g-1")).andExpect(status().isNotFound());
	}

	@Test
	void recordingAResultOverHttp() throws Exception {
		var kwame = TestSignIn.as(mvc, "024 455 5123");
		var game = json.readTree(mvc.perform(post("/games").cookie(kwame).contentType(MediaType.APPLICATION_JSON)
			.content(NEW_GAME.replace("2030-06-02T16:00:00.000Z", java.time.Instant.now().plusSeconds(2).toString())))
			.andReturn().getResponse().getContentAsString());
		var id = game.get("id").asString();
		var kojo = TestSignIn.as(mvc, "024 455 5124");
		var kojoId = json.readTree(mvc.perform(post("/games/" + id + "/players").cookie(kojo)).andReturn().getResponse().getContentAsString())
			.get("players").get(1).get("id").asString();
		var result = """
				{"homeScore":2,"awayScore":1,"sides":{"home":["%s"],"away":["%s"]},"scorers":[{"userId":"%s","goals":1}]}
				""".formatted(game.get("hostId").asString(), kojoId, kojoId);
		// Kick-off is two seconds after the game was made: wait for it rather than guess how long that takes on this machine.
		var deadline = System.currentTimeMillis() + 10_000;
		var recorded = mvc.perform(put("/games/" + id + "/result").cookie(kwame).contentType(MediaType.APPLICATION_JSON).content(result));
		while (recorded.andReturn().getResponse().getContentAsString().contains("once the game has started") && System.currentTimeMillis() < deadline) {
			Thread.sleep(250);
			recorded = mvc.perform(put("/games/" + id + "/result").cookie(kwame).contentType(MediaType.APPLICATION_JSON).content(result));
		}

		recorded.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("completed"))
			.andExpect(jsonPath("$.result.homeScore").value(2))
			.andExpect(jsonPath("$.result.scorers[0].goals").value(1))
			.andExpect(jsonPath("$.result.scorers[0].assists").doesNotExist());

		mvc.perform(post("/games/" + id + "/result/disputes").cookie(kojo).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"2-2\"}"))
			.andExpect(jsonPath("$.result.disputes[0].reason").value("2-2"));
		mvc.perform(get("/users/" + kojoId + "/profile"))
			.andExpect(jsonPath("$.stats.games").value(1))
			.andExpect(jsonPath("$.stats.goals").value(1))
			.andExpect(jsonPath("$.form[0]").value("L"));
		mvc.perform(get("/users/" + kojoId + "/history")).andExpect(jsonPath("$[0].scoreFor").value(1));
	}

}
