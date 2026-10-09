package com.playchale.api.games.web;

import java.time.Instant;

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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Joining a game without an account: someone looking around takes a spot as a guest, the host
 * hears about it and can reach them, and the spot (with its result, once played) becomes theirs
 * when they sign in with the number they gave.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GuestSpotsApiTest {

	private static final String GAME = """
			{"sport":"football","format":"5-a-side","title":"Sunday 5s","startsAt":"%s","durationMinutes":60,
			 "venue":{"kind":"unlisted","name":"Legon Park","area":"Legon"},"capacity":10,"totalCost":%d,"visibility":"%s"}
			""";

	private static final String KOFI = """
			{"name":"Kofi","phone":"024 455 5130","email":"Kofi@Example.com"}
			""";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper json;

	private Cookie host;

	@BeforeEach
	void setUp() throws Exception {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, games, game_participants, notifications, rate_limits, audit_events CASCADE").update();
		host = TestSignIn.as(mvc, "024 455 5123");
	}

	@Test
	void aVisitorTakesASpotAndOnlyTheHostSeesHowToReachThem() throws Exception {
		var id = game("2030-06-02T16:00:00Z", 25000, "public");
		var joined = body(guestJoins(id, KOFI), 201);
		assertThat(joined.get("token").asString()).isNotBlank();
		var guest = joined.at("/game/players/1");
		assertThat(guest.get("name").asString()).isEqualTo("Kofi");
		assertThat(guest.at("/guest/selfJoined").asBoolean()).isTrue();
		// The spot's public ID, so the browser can find its own spot in the game.
		assertThat(guest.at("/guest/token").asString()).isEqualTo(joined.get("spot").asString());
		// The guest's own answer, like anyone else's view, doesn't carry their number.
		assertThat(guest.at("/guest").has("phone")).isFalse();

		var asHost = body(mvc.perform(get("/games/" + id).cookie(host)), 200);
		assertThat(asHost.at("/players/1/guest/phone").asString()).isEqualTo("+233244555130");
		assertThat(asHost.at("/players/1/guest/email").asString()).isEqualTo("kofi@example.com");
		assertThat(asHost.get("spotsLeft").asInt()).isEqualTo(8);
		mvc.perform(get("/notifications").cookie(host)).andExpect(jsonPath("$[0].title").value("Kofi joined Sunday 5s as a guest"));

		// One spot a number, and a signed-in player joins as themselves.
		guestJoins(id, """
				{"name":"Kofi again","phone":"0244555130"}
				""").andExpect(status().isConflict());
		guestJoins(id, """
				{"name":"Ama","phone":"","email":"ama@example.com"}
				""").andExpect(status().isUnprocessableEntity());
		var player = TestSignIn.as(mvc, "024 455 5140");
		mvc.perform(post("/games/" + id + "/guest-spots").cookie(player).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Esi\",\"phone\":\"024 455 5141\"}")).andExpect(status().isConflict());
	}

	@Test
	void aPrivateGameIsTheHostsToFill() throws Exception {
		var id = game("2030-06-02T16:00:00Z", 0, "private");
		guestJoins(id, KOFI).andExpect(status().isConflict()).andExpect(jsonPath("$.error.message").value("This game is private. Ask the host to add you."));
	}

	@Test
	void aGuestLeavesWithTheirTokenUntilTheHostHasTheirCash() throws Exception {
		var id = game("2030-06-02T16:00:00Z", 25000, "public");
		var token = body(guestJoins(id, KOFI), 201).get("token").asString();
		mvc.perform(delete("/games/" + id + "/guest-spots/not-the-token")).andExpect(status().isNotFound());
		mvc.perform(delete("/games/" + id + "/guest-spots/" + token)).andExpect(status().isOk()).andExpect(jsonPath("$.players.length()").value(1));

		var again = body(guestJoins(id, KOFI), 201);
		var spot = again.at("/game/players/1/id").asString();
		mvc.perform(post("/games/" + id + "/players/" + spot + "/cash").cookie(host)).andExpect(status().isOk());
		mvc.perform(delete("/games/" + id + "/guest-spots/" + again.get("token").asString())).andExpect(status().isConflict());
	}

	@Test
	void signingInWithTheNumberMakesTheSpotTheirs() throws Exception {
		var id = game("2030-06-02T16:00:00Z", 0, "public");
		body(guestJoins(id, KOFI), 201);

		var kofi = TestSignIn.as(mvc, "024 455 5130");
		var mine = body(mvc.perform(get("/me/games").cookie(kofi)), 200);
		assertThat(mine.get(0).get("id").asString()).isEqualTo(id);
		var game = body(mvc.perform(get("/games/" + id).cookie(kofi)), 200);
		assertThat(game.at("/players/1/guest").isMissingNode()).isTrue();
		assertThat(game.at("/participants/1/userId").asString()).isNotBlank();
	}

	@Test
	void theBrowserTheyJoinedInCanClaimItWhateverTheySignInWith() throws Exception {
		var id = game("2030-06-02T16:00:00Z", 0, "public");
		var token = body(guestJoins(id, KOFI), 201).get("token").asString();
		var other = TestSignIn.as(mvc, "024 455 5139");
		mvc.perform(post("/games/" + id + "/claims").cookie(other).contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"%s\"}".formatted(token))).andExpect(status().isOk()).andExpect(jsonPath("$.players.length()").value(2));
		mvc.perform(get("/me/games").cookie(other)).andExpect(jsonPath("$[0].id").value(id));
	}

	@Test
	void aGamePlayedAsAGuestCountsOnTheirProfileOnceTheySignUp() throws Exception {
		var id = game(Instant.now().plusSeconds(2).toString(), 0, "public");
		var guest = body(guestJoins(id, KOFI), 201).at("/game/players/1/id").asString();
		var hostId = body(mvc.perform(get("/games/" + id).cookie(host)), 200).get("hostId").asString();
		var result = """
				{"homeScore":1,"awayScore":2,"sides":{"home":["%s"],"away":["%s"]},"scorers":[{"userId":"%s","goals":2}]}
				""".formatted(hostId, guest, guest);
		// Kick-off is two seconds after the game was made: wait for it.
		var deadline = System.currentTimeMillis() + 10_000;
		var recorded = mvc.perform(put("/games/" + id + "/result").cookie(host).contentType(MediaType.APPLICATION_JSON).content(result));
		while (recorded.andReturn().getResponse().getContentAsString().contains("once the game has started") && System.currentTimeMillis() < deadline) {
			Thread.sleep(250);
			recorded = mvc.perform(put("/games/" + id + "/result").cookie(host).contentType(MediaType.APPLICATION_JSON).content(result));
		}
		recorded.andExpect(status().isOk());

		var kofi = TestSignIn.as(mvc, "024 455 5130");
		var me = body(mvc.perform(get("/auth/session").cookie(kofi)), 200).get("id").asString();
		mvc.perform(get("/users/" + me + "/profile"))
			.andExpect(jsonPath("$.stats.games").value(1))
			.andExpect(jsonPath("$.stats.goals").value(2))
			.andExpect(jsonPath("$.form[0]").value("W"));
		var game = body(mvc.perform(get("/games/" + id).cookie(kofi)), 200);
		assertThat(game.at("/result/sides/away/0").asString()).isEqualTo(me);
	}

	@Test
	void oneConnectionCanOnlyJoinSoMuchWithoutAnAccount() throws Exception {
		var first = game("2030-06-02T16:00:00Z", 0, "public");
		var second = game("2030-06-03T16:00:00Z", 0, "public");
		for (int i = 0; i < 10; i++) {
			guestJoins(i < 5 ? first : second, "{\"name\":\"Guest %d\",\"phone\":\"024 455 52%02d\"}".formatted(i, i)).andExpect(status().isCreated());
		}
		guestJoins(second, "{\"name\":\"One too many\",\"phone\":\"024 455 5299\"}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.startsWith("Too many games joined")));
	}

	/* ------------------------------------------------------------------ */

	private String game(String startsAt, long totalCost, String visibility) throws Exception {
		return body(mvc.perform(post("/games").cookie(host).contentType(MediaType.APPLICATION_JSON)
			.content(GAME.formatted(startsAt, totalCost, visibility))), 201).get("id").asString();
	}

	private ResultActions guestJoins(String gameId, String body) throws Exception {
		return mvc.perform(post("/games/" + gameId + "/guest-spots").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private JsonNode body(ResultActions result, int status) throws Exception {
		var response = result.andReturn().getResponse();
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		return json.readTree(response.getContentAsString());
	}

}
