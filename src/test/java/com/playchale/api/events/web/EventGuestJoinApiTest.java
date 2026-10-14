package com.playchale.api.events.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.UUID;

import com.playchale.api.TestSignIn;
import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.events.internal.service.EventGuestCleanup;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Signing up to a games day through its link without an account: a name, a number and the games
 * they want, a place kept on their phone, and the place theirs once they sign up with that number.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EventGuestJoinApiTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper json;

	@Autowired
	EventGuestCleanup cleanup;

	private Cookie admin;

	private Cookie player;

	private String eventId;

	private String joy;

	private String tableTennis;

	private String football;

	private String code;

	@BeforeEach
	void setUp() throws Exception {
		jdbc.sql("""
				TRUNCATE users, sign_in_codes, sessions, organisations, organisation_memberships, organisation_invitations,
				         events, notifications, audit_events, rate_limits CASCADE
				""").update();
		admin = TestSignIn.as(mvc, "024 455 5401");
		player = TestSignIn.as(mvc, "024 455 5402");
		send(patch("/me"), player, "{\"name\":\"Ama Owusu\"}").andExpect(status().isOk());
		var event = ok(send(post("/events"), admin, """
				{"workspace":{"name":"Hillview Chapel"},"event":{"name":"Hillview Games Day","startsOn":"2030-06-01","country":"GH",
				 "groups":[{"name":"Joy Fellowship"},{"name":"Hope Fellowship"}]}}
				"""), 201);
		eventId = event.at("/event/id").asString();
		joy = event.at("/groups/0/id").asString();
		tableTennis = addGame("{\"discipline\":\"table-tennis\"}");
		football = addGame("{\"discipline\":\"football\"}");
		code = ok(mvc.perform(get("/events/" + eventId).cookie(admin)), 200).at("/links/joinUrl").asString().split("code=")[1];
		// The player joins with their account, to see what the others see.
		send(post("/events/join/" + code), player, "{\"groupId\":\"%s\",\"gameIds\":[]}".formatted(joy)).andExpect(status().isOk());
	}

	@Test
	void someoneWithoutAnAccountSignsUpAndKeepsTheirPlaceOnTheirPhone() throws Exception {
		// The link opens without signing in, and says which country's numbers it takes.
		var preview = ok(mvc.perform(get("/events/join/" + code)), 200);
		assertThat(preview.get("name").asString()).isEqualTo("Hillview Games Day");
		assertThat(preview.get("country").asString()).isEqualTo("GH");
		assertThat(preview.has("mine")).isFalse();

		var joined = ok(send(post("/events/join/" + code + "/guests"), null, """
				{"name":"Kobby Guest","phone":"024 455 5499","groupId":"%s","gameIds":["%s","%s"]}
				""".formatted(joy, tableTennis, football)), 201);
		var token = joined.get("token").asString();

		var place = ok(mvc.perform(get("/events/guest-places/" + token)), 200);
		assertThat(place.get("name").asString()).isEqualTo("Kobby Guest");
		assertThat(place.at("/event/mine/groupId").asString()).isEqualTo(joy);
		assertThat(place.at("/event/mine/gameIds").size()).isEqualTo(2);

		// The organisers see the number; another player doesn't.
		var asAdmin = find(ok(mvc.perform(get("/events/" + eventId).cookie(admin)), 200).get("people"), "Kobby Guest");
		assertThat(asAdmin.get("source").asString()).isEqualTo("guest");
		assertThat(asAdmin.get("guestPhone").asString()).isEqualTo("+233244555499");
		var asPlayer = find(ok(mvc.perform(get("/events/" + eventId).cookie(player)), 200).get("people"), "Kobby Guest");
		assertThat(asPlayer.has("guestPhone")).isFalse();

		// Their games change, then they leave.
		place = ok(send(put("/events/guest-places/" + token), null, "{\"groupId\":\"%s\",\"gameIds\":[\"%s\"]}".formatted(joy, football)), 200);
		assertThat(place.at("/event/mine/gameIds").size()).isEqualTo(1);
		mvc.perform(delete("/events/guest-places/" + token)).andExpect(status().isNoContent());
		mvc.perform(get("/events/guest-places/" + token)).andExpect(status().isNotFound());
	}

	@Test
	void aNumberSignsUpOnceAndOnlyWhileTheEventTakesSignUps() throws Exception {
		var body = "{\"name\":\"Kobby Guest\",\"phone\":\"024 455 5499\",\"groupId\":\"%s\",\"gameIds\":[]}".formatted(joy);
		send(post("/events/join/" + code + "/guests"), null, body).andExpect(status().isCreated());
		send(post("/events/join/" + code + "/guests"), null, body).andExpect(status().isConflict());
		// A number that isn't one, no group, someone signed in.
		send(post("/events/join/" + code + "/guests"), null, "{\"name\":\"Esi\",\"phone\":\"12\",\"groupId\":\"%s\"}".formatted(joy))
			.andExpect(status().isUnprocessableEntity());
		send(post("/events/join/" + code + "/guests"), null, "{\"name\":\"Esi\",\"phone\":\"024 455 5498\"}").andExpect(status().isUnprocessableEntity());
		send(post("/events/join/" + code + "/guests"), player, body.replace("5499", "5497")).andExpect(status().isConflict());
		// Sign-ups closed.
		jdbc.sql("UPDATE events SET registration_open = false WHERE id = :id").param("id", UUID.fromString(eventId)).update();
		send(post("/events/join/" + code + "/guests"), null, body.replace("5499", "5496")).andExpect(status().isConflict());
	}

	@Test
	void signingInWithTheNumberMakesThePlaceTheirs() throws Exception {
		send(post("/events/join/" + code + "/guests"), null, """
				{"name":"Kobby Guest","phone":"024 455 5499","groupId":"%s","gameIds":["%s"]}
				""".formatted(joy, tableTennis)).andExpect(status().isCreated());

		var kobby = TestSignIn.as(mvc, "024 455 5499");
		var event = ok(mvc.perform(get("/events/" + eventId).cookie(kobby)), 200);
		assertThat(event.at("/viewer/role").asString()).isEqualTo("player");
		var person = find(event.get("people"), "Kobby Guest");
		assertThat(person.has("userId")).isTrue();
		assertThat(heldNumbers()).as("the number is theirs now; no need to hold it").isZero();
	}

	@Test
	void aPlaceHandedToAnAccountAlreadyInTheEventIsRefused() throws Exception {
		var token = ok(send(post("/events/join/" + code + "/guests"), null, """
				{"name":"Ama Again","phone":"024 455 5499","groupId":"%s","gameIds":[]}
				""".formatted(joy)), 201).get("token").asString();
		send(post("/events/guest-places/" + token + "/claim"), player, null).andExpect(status().isConflict());

		var newcomer = TestSignIn.as(mvc, "024 455 5403");
		var event = ok(send(post("/events/guest-places/" + token + "/claim"), newcomer, null), 200);
		assertThat(event.at("/viewer/role").asString()).isEqualTo("player");
	}

	@Test
	void aGuestsNumberIsForgottenNinetyDaysAfterTheEvent() throws Exception {
		send(post("/events/join/" + code + "/guests"), null, """
				{"name":"Kobby Guest","phone":"024 455 5499","groupId":"%s","gameIds":[]}
				""".formatted(joy)).andExpect(status().isCreated());
		jdbc.sql("UPDATE events SET starts_on = :day, ends_on = :day WHERE id = :id").param("day", LocalDate.now().minusDays(120))
			.param("id", UUID.fromString(eventId)).update();
		cleanup.tidy();
		assertThat(heldNumbers()).isZero();
	}

	/** Guest numbers still held. */
	private int heldNumbers() {
		return jdbc.sql("SELECT count(*) FROM event_people WHERE guest_phone IS NOT NULL").query(Integer.class).single();
	}

	private String addGame(String body) throws Exception {
		var games = ok(send(post("/events/" + eventId + "/games"), admin, body), 200).get("games");
		return games.get(games.size() - 1).get("id").asString();
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

	private static JsonNode find(JsonNode people, String name) {
		var found = new ArrayList<JsonNode>();
		people.forEach(n -> {
			if (name.equals(n.get("name").asString())) {
				found.add(n);
			}
		});
		assertThat(found).as("someone called %s", name).isNotEmpty();
		return found.getFirst();
	}

}
