package com.playchale.api.events.web;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Someone the organisers added by name attaches their own account with the link they're sent, and
 * then the games they played, and where they placed, are on their profile.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EventClaimsApiTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper json;

	private Cookie admin;

	private String eventId;

	private String kofi;

	private String ama;

	@BeforeEach
	void setUp() throws Exception {
		jdbc.sql("""
				TRUNCATE users, sign_in_codes, sessions, organisations, organisation_memberships, organisation_invitations,
				         events, notifications, audit_events CASCADE
				""").update();
		admin = TestSignIn.as(mvc, "024 455 5401");
		var workspace = ok(send(post("/organisations"), admin, """
				{"name":"Hillview Chapel","slug":"hillview-chapel","country":"GH","primaryColour":"#0c3a3a"}
				"""), 201);
		var event = ok(send(post("/organisations/" + workspace.at("/organisation/id").asString() + "/events"), admin, """
				{"name":"Hillview Games Day","startsOn":"2030-06-01","groups":[{"name":"Joy Fellowship"},{"name":"Hope Fellowship"}]}
				"""), 201);
		eventId = event.at("/event/id").asString();
		var joy = event.at("/groups/0/id").asString();
		var hope = event.at("/groups/1/id").asString();
		var people = ok(send(post("/events/" + eventId + "/people"), admin, """
				{"people":[{"name":"Kofi Mensah","groupId":"%s"},{"name":"Ama Owusu","groupId":"%s"}]}
				""".formatted(joy, hope)), 200).get("people");
		kofi = idOf(people, "Kofi Mensah");
		ama = idOf(people, "Ama Owusu");
	}

	@Test
	void theLinkSentToAPersonAttachesTheirAccountAndTheirPlacesGoOnTheirProfile() throws Exception {
		// Oware, knockout: Kofi beats Ama in the final, and the event is over.
		var games = ok(send(post("/events/" + eventId + "/games"), admin, "{\"discipline\":\"oware\"}"), 200).get("games");
		var game = games.get(games.size() - 1).get("id").asString();
		var path = "/events/" + eventId + "/games/" + game;
		ok(send(post(path + "/entries"), admin, "{\"personIds\":[\"%s\"]}".formatted(kofi)), 200);
		ok(send(post(path + "/entries"), admin, "{\"personIds\":[\"%s\"]}".formatted(ama)), 200);
		var drawn = ok(send(post(path + "/draw"), admin, null), 200);
		var finalMatch = find(drawn.get("games"), game).get("matches").get(0);
		var kofiHome = entryOf(find(drawn.get("games"), game), kofi).equals(finalMatch.get("homeEntryId").asString());
		ok(send(put("/events/" + eventId + "/matches/" + finalMatch.get("id").asString() + "/result"), admin,
				"{\"winner\":\"%s\"}".formatted(kofiHome ? "home" : "away")), 200);
		ok(send(post("/events/" + eventId + "/finish"), admin, null), 200);

		// The organisers send Kofi his link. Anyone can see what it's for; nobody else's details.
		var link = ok(send(post("/events/" + eventId + "/people/" + kofi + "/claim-link"), admin, null), 200).get("url").asString();
		var token = link.substring(link.indexOf("token=") + 6);
		var preview = ok(mvc.perform(get("/events/claims/" + token)), 200);
		assertThat(preview.get("personName").asString()).isEqualTo("Kofi Mensah");
		assertThat(preview.get("eventName").asString()).isEqualTo("Hillview Games Day");
		assertThat(preview.at("/group/name").asString()).isEqualTo("Joy Fellowship");
		assertThat(preview.get("games").asInt()).isEqualTo(1);
		assertThat(preview.get("claimed").asBoolean()).isFalse();

		// He signs in and says it's him: the event is his to open now, and his result is on his profile.
		var kofiAccount = TestSignIn.as(mvc, "024 455 5402");
		var claimed = ok(send(post("/events/claims/" + token), kofiAccount, null), 200);
		assertThat(claimed.at("/viewer/personId").asString()).isEqualTo(kofi);
		var userId = jdbc.sql("SELECT id::text FROM users WHERE phone = '+233244555402'").query(String.class).single();
		var results = ok(mvc.perform(get("/users/" + userId + "/event-results")), 200);
		assertThat(results.size()).isEqualTo(1);
		assertThat(results.at("/0/name").asString()).isEqualTo("Hillview Games Day");
		assertThat(results.at("/0/organisationName").asString()).isEqualTo("Hillview Chapel");
		assertThat(results.at("/0/group/name").asString()).isEqualTo("Joy Fellowship");
		assertThat(results.at("/0/groupPlace").asInt()).isEqualTo(1);
		assertThat(results.at("/0/games/0/name").asString()).isEqualTo("Oware");
		assertThat(results.at("/0/games/0/place").asInt()).isEqualTo(1);

		// Opening it again says it's his; to anyone else, that it's been used.
		assertThat(ok(send(get("/events/claims/" + token), kofiAccount, null), 200).get("mine").asBoolean()).isTrue();
		ok(send(post("/events/claims/" + token), kofiAccount, null), 200);
		var stranger = TestSignIn.as(mvc, "024 455 5403");
		assertThat(ok(send(get("/events/claims/" + token), stranger, null), 200).get("claimed").asBoolean()).isTrue();
		ok(send(post("/events/claims/" + token), stranger, null), 409);
		// And there's no new link for someone already in with their account.
		ok(send(post("/events/" + eventId + "/people/" + kofi + "/claim-link"), admin, null), 409);
	}

	@Test
	void aNewLinkReplacesTheLastAndOnlyAnAdminSendsOne() throws Exception {
		var first = token(ok(send(post("/events/" + eventId + "/people/" + ama + "/claim-link"), admin, null), 200));
		var second = token(ok(send(post("/events/" + eventId + "/people/" + ama + "/claim-link"), admin, null), 200));
		ok(mvc.perform(get("/events/claims/" + first)), 404);
		ok(mvc.perform(get("/events/claims/" + second)), 200);

		var player = TestSignIn.as(mvc, "024 455 5404");
		ok(send(post("/events/" + eventId + "/people/" + ama + "/claim-link"), player, null), 404);
		ok(mvc.perform(get("/events/claims/not-a-real-link")), 404);
	}

	@Test
	void someoneAlreadyInUnderAnotherNameCantClaimASecond() throws Exception {
		var link = token(ok(send(post("/events/" + eventId + "/people/" + ama + "/claim-link"), admin, null), 200));
		var kofiLink = token(ok(send(post("/events/" + eventId + "/people/" + kofi + "/claim-link"), admin, null), 200));
		var account = TestSignIn.as(mvc, "024 455 5405");
		ok(send(post("/events/claims/" + link), account, null), 200);
		var refused = send(post("/events/claims/" + kofiLink), account, null).andReturn().getResponse();
		assertThat(refused.getStatus()).isEqualTo(409);
		assertThat(json.readTree(refused.getContentAsString()).at("/error/message").asString())
			.isEqualTo("You’re already in Hillview Games Day as Ama Owusu. Ask the organisers to remove one of the two first.");
		// An event still going isn't on anyone's profile yet.
		var userId = jdbc.sql("SELECT id::text FROM users WHERE phone = '+233244555405'").query(String.class).single();
		assertThat(ok(mvc.perform(get("/users/" + userId + "/event-results")), 200).size()).isZero();
	}

	private static String token(JsonNode link) {
		var url = link.get("url").asString();
		return url.substring(url.indexOf("token=") + 6);
	}

	private static String idOf(JsonNode people, String name) {
		for (var p : people) {
			if (name.equals(p.get("name").asString())) {
				return p.get("id").asString();
			}
		}
		throw new AssertionError("no " + name);
	}

	private static String entryOf(JsonNode game, String personId) {
		for (var e : game.get("entries")) {
			for (var p : e.get("personIds")) {
				if (personId.equals(p.asString())) {
					return e.get("id").asString();
				}
			}
		}
		throw new AssertionError("no entry for " + personId);
	}

	private static JsonNode find(JsonNode list, String id) {
		for (var n : list) {
			if (id.equals(n.get("id").asString())) {
				return n;
			}
		}
		throw new AssertionError("no " + id);
	}

	private ResultActions send(MockHttpServletRequestBuilder request, Cookie who, String body) throws Exception {
		request.cookie(who);
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

	private JsonNode ok(ResultActions result, int status) throws Exception {
		var response = result.andReturn().getResponse();
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		return json.readTree(response.getContentAsString());
	}

}
