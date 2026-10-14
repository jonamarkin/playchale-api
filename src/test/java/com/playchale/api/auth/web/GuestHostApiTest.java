package com.playchale.api.auth.web;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Hosting a game without an account: a guest host, what it can and can't do, the manage link, and
 * becoming an account (the same one) or moving into the one they already had.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GuestHostApiTest {

	private static final String PRIVATE_GAME = """
			{"sport":"football","format":"5-a-side","startsAt":"2030-06-02T16:00:00.000Z","durationMinutes":60,
			 "venue":{"kind":"unlisted","name":"Legon Park","area":"Legon"},"capacity":10,"totalCost":0,"visibility":"private"}
			""";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper json;

	@BeforeEach
	void emptyTables() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, games, game_participants, notifications, rate_limits, host_keys CASCADE").update();
	}

	@Test
	void aGuestHostsAnInviteOnlyGameAndNothingElse() throws Exception {
		var started = becomeGuest("Ekow Brown", "024 455 5601");
		var guest = started.cookie();
		assertThat(started.body().get("manageKey").asString()).isNotBlank();
		assertThat(ok(mvc.perform(get("/auth/session").cookie(guest)), 200).get("guestHost").asBoolean()).isTrue();

		// Invite only, at a typed-in place.
		var game = ok(send(post("/games"), guest, PRIVATE_GAME), 201);
		assertThat(game.at("/host/name").asString()).isEqualTo("Ekow Brown");
		assertThat(game.at("/host/guestHost").asBoolean()).isTrue();
		var id = game.get("id").asString();
		send(patch("/games/" + id), guest, PRIVATE_GAME.replace("\"private\"", "\"public\"")).andExpect(status().isUnprocessableEntity());

		// Not in Discover; not a partner pitch; not anything that needs an account.
		send(post("/games"), guest, PRIVATE_GAME.replace("\"private\"", "\"public\"")).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.message").value("Without an account, a game is invite only. Sign up to list it in Discover."));
		mvc.perform(get("/me/events").cookie(guest)).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.message").value("Sign up to do that: it takes a minute, and the games you host come with you."));
		send(patch("/me"), guest, "{\"handle\":\"ekow\"}").andExpect(status().isUnauthorized());
		mvc.perform(get("/me/games").cookie(guest)).andExpect(status().isOk());

		// In someone else's game they're anyone without an account: a guest spot, like any visitor.
		var host = TestSignIn.as(mvc, "024 455 5610");
		var other = ok(send(post("/games"), host, PRIVATE_GAME.replace("\"private\"", "\"public\"")), 201).get("id").asString();
		send(post("/games/" + other + "/guest-spots"), guest, "{\"name\":\"Ekow\",\"phone\":\"024 455 5601\"}").andExpect(status().isCreated());
	}

	@Test
	void aNumberWithAnAccountSignsInInstead() throws Exception {
		TestSignIn.as(mvc, "024 455 5602");
		guestRequest("Esi", "024 455 5602").andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.message").value("That number has a PlayChale account. Sign in with it instead."));
		becomeGuest("Ama", "024 455 5603");
		guestRequest("Ama Again", "024 455 5603").andExpect(status().isConflict());
		guestRequest("Ama", "12").andExpect(status().isUnprocessableEntity());
	}

	@Test
	void theManageLinkOpensItOnAnotherBrowserUntilItsReplaced() throws Exception {
		var started = becomeGuest("Ekow Brown", "024 455 5604");
		send(post("/games"), started.cookie(), PRIVATE_GAME).andExpect(status().isCreated());
		var key = started.body().get("manageKey").asString();

		var elsewhere = cookie(send(post("/auth/host-keys/sessions"), null, "{\"key\":\"%s\"}".formatted(key)).andExpect(status().isOk()));
		assertThat(ok(mvc.perform(get("/me/games").cookie(elsewhere)), 200).size()).isEqualTo(1);

		var replaced = ok(send(post("/auth/host-keys"), elsewhere, null), 200).get("manageKey").asString();
		send(post("/auth/host-keys/sessions"), null, "{\"key\":\"%s\"}".formatted(key)).andExpect(status().isNotFound());
		send(post("/auth/host-keys/sessions"), null, "{\"key\":\"%s\"}".formatted(replaced)).andExpect(status().isOk());
	}

	@Test
	void signingUpOnTheSameBrowserKeepsTheSameAccountAndItsGames() throws Exception {
		var started = becomeGuest("Ekow Brown", "024 455 5605");
		var guestId = started.body().at("/user/id").asString();
		send(post("/games"), started.cookie(), PRIVATE_GAME).andExpect(status().isCreated());

		// An email nobody has, on the device that hosts: that account, now theirs to sign in with.
		send(post("/auth/codes"), null, "{\"email\":\"ekow@example.com\"}").andExpect(status().isOk());
		var signedIn = send(post("/auth/sessions"), started.cookie(), "{\"email\":\"ekow@example.com\",\"code\":\"123456\"}").andExpect(status().isOk());
		var user = ok(signedIn, 200);
		assertThat(user.get("id").asString()).isEqualTo(guestId);
		assertThat(user.has("guestHost")).isFalse();
		var member = cookie(signedIn);
		assertThat(ok(mvc.perform(get("/me/games").cookie(member)), 200).size()).isEqualTo(1);
		// No longer limited to what a guest can do.
		mvc.perform(get("/me/events").cookie(member)).andExpect(status().isOk());
	}

	@Test
	void signingInWithTheNumberFromAnotherPhoneMakesItTheirs() throws Exception {
		var started = becomeGuest("Ekow Brown", "024 455 5606");
		var guestId = started.body().at("/user/id").asString();
		send(post("/games"), started.cookie(), PRIVATE_GAME).andExpect(status().isCreated());

		var phone = TestSignIn.as(mvc, "024 455 5606");
		var me = ok(mvc.perform(get("/auth/session").cookie(phone)), 200);
		assertThat(me.get("id").asString()).isEqualTo(guestId);
		assertThat(me.has("guestHost")).isFalse();
		// The browser it hosted from is an ordinary account's now too.
		mvc.perform(get("/me/events").cookie(started.cookie())).andExpect(status().isOk());
	}

	@Test
	void signingInToAnAccountTheyAlreadyHadMovesTheGamesThere() throws Exception {
		var existing = TestSignIn.as(mvc, "024 455 5607");
		var existingId = ok(mvc.perform(get("/auth/session").cookie(existing)), 200).get("id").asString();
		var started = becomeGuest("Ekow Brown", "024 455 5608");
		var game = ok(send(post("/games"), started.cookie(), PRIVATE_GAME), 201).get("id").asString();

		send(post("/auth/codes"), null, "{\"phone\":\"024 455 5607\"}").andExpect(status().isOk());
		var signedIn = send(post("/auth/sessions"), started.cookie(), "{\"phone\":\"024 455 5607\",\"code\":\"123456\"}").andExpect(status().isOk());
		assertThat(ok(signedIn, 200).get("id").asString()).isEqualTo(existingId);

		var host = jdbc.sql("SELECT host_id FROM games WHERE id = :id").param("id", UUID.fromString(game)).query(UUID.class).single();
		assertThat(host.toString()).isEqualTo(existingId);
		var spot = jdbc.sql("SELECT count(*) FROM game_participants WHERE game_id = :id AND user_id = :user")
			.param("id", UUID.fromString(game)).param("user", UUID.fromString(existingId)).query(Integer.class).single();
		assertThat(spot).isOne();
		// The guest account is retired, and its sessions with it.
		var retired = jdbc.sql("SELECT deleted_at IS NOT NULL FROM users WHERE id = :id")
			.param("id", UUID.fromString(started.body().at("/user/id").asString())).query(Boolean.class).single();
		assertThat(retired).isTrue();
		assertThat(ok(mvc.perform(get("/auth/session").cookie(started.cookie())), 200).isNull()).isTrue();
	}

	private record Started(Cookie cookie, JsonNode body) {
	}

	private Started becomeGuest(String name, String phone) throws Exception {
		var result = guestRequest(name, phone).andExpect(status().isCreated());
		return new Started(cookie(result), ok(result, 201));
	}

	private ResultActions guestRequest(String name, String phone) throws Exception {
		return send(post("/auth/guest-hosts"), null, "{\"name\":\"%s\",\"phone\":\"%s\",\"country\":\"GH\",\"termsVersion\":\"%s\"}"
			.formatted(name, phone, Terms.CURRENT));
	}

	private static Cookie cookie(ResultActions result) {
		var header = result.andReturn().getResponse().getHeader("Set-Cookie");
		return new Cookie("playchale_session", header.split(";")[0].split("=", 2)[1]);
	}

	private ResultActions send(MockHttpServletRequestBuilder request, Cookie who, String body) throws Exception {
		if (who != null) {
			request.cookie(who);
		}
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

	private JsonNode ok(ResultActions result, int status) throws Exception {
		return json.readTree(result.andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
	}

}
