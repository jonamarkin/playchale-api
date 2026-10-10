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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * What the organisers tell everyone on the day, and the entry fee they collect themselves: who's
 * told, who sees what, and that PlayChale only keeps track.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EventNewsAndFeesApiTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper json;

	private Cookie admin;

	private Cookie player;

	private String eventId;

	private String boardUrl;

	@BeforeEach
	void setUp() throws Exception {
		jdbc.sql("""
				TRUNCATE users, sign_in_codes, sessions, organisations, organisation_memberships, organisation_invitations,
				         events, notifications, audit_events CASCADE
				""").update();
		admin = TestSignIn.as(mvc, "024 455 5501");
		player = TestSignIn.as(mvc, "024 455 5502");
		var workspace = ok(send(post("/organisations"), admin, """
				{"name":"Hillview Chapel","slug":"hillview-chapel","country":"GH","primaryColour":"#0c3a3a"}
				"""), 201);
		var event = ok(send(post("/organisations/" + workspace.at("/organisation/id").asString() + "/events"), admin, """
				{"name":"Hillview Games Day","startsOn":"2030-06-01"}
				"""), 201);
		eventId = event.at("/event/id").asString();
		boardUrl = event.at("/links/boardUrl").asString();
		var code = event.at("/links/joinUrl").asString().split("code=")[1];
		ok(send(post("/events/join/" + code), player, "{\"gameIds\":[]}"), 200);
		ok(send(post("/events/" + eventId + "/people"), admin, "{\"people\":[{\"name\":\"Kofi Mensah\"}]}"), 200);
	}

	@Test
	void anAnnouncementIsOnThePageAndTheBoardAndEveryoneWithAnAccountHears() throws Exception {
		var told = ok(send(post("/events/" + eventId + "/announcements"), admin, "{\"body\":\"  The football final moves to Court 2 at 3.  \"}"), 200);
		assertThat(told.at("/announcements/0/body").asString()).isEqualTo("The football final moves to Court 2 at 3.");
		assertThat(told.at("/announcements/0/postedAt").asString()).isNotBlank();

		var seen = ok(send(get("/events/" + eventId), player, null), 200);
		assertThat(seen.at("/announcements/0/body").asString()).isEqualTo("The football final moves to Court 2 at 3.");
		assertThat(jdbc.sql("SELECT body FROM notifications WHERE kind = 'event'").query(String.class).list())
			.containsExactly("The football final moves to Court 2 at 3.");
		var board = ok(mvc.perform(get("/boards/" + boardUrl.substring(boardUrl.lastIndexOf('/') + 1))), 200);
		assertThat(board.at("/announcement/body").asString()).isEqualTo("The football final moves to Court 2 at 3.");

		// Only admins post or take one down; an empty one is refused.
		send(post("/events/" + eventId + "/announcements"), player, "{\"body\":\"Free food!\"}").andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(403, 404, 409));
		ok(send(post("/events/" + eventId + "/announcements"), admin, "{\"body\":\"   \"}"), 422);
		var id = told.at("/announcements/0/id").asString();
		var gone = ok(send(delete("/events/" + eventId + "/announcements/" + id), admin, null), 200);
		assertThat(gone.get("announcements").size()).isZero();
		ok(send(delete("/events/" + eventId + "/announcements/" + id), admin, null), 404);
	}

	@Test
	void anEntryFeeIsTrackedAndOnlyTheOrganisersSeeWhoHasPaid() throws Exception {
		// No fee yet: nothing to mark.
		var kofi = personNamed(ok(send(get("/events/" + eventId), admin, null), 200), "Kofi Mensah");
		ok(send(put("/events/" + eventId + "/people/" + kofi + "/fee"), admin, "{\"via\":\"cash\"}"), 409);

		var priced = ok(send(patch("/events/" + eventId), admin, """
				{"name":"Hillview Games Day","startsOn":"2030-06-01","entryFee":1000}
				"""), 200);
		assertThat(priced.at("/event/entryFee").asLong()).isEqualTo(1000);
		assertThat(priced.at("/event/currency").asString()).isEqualTo("GHS");

		var marked = ok(send(put("/events/" + eventId + "/people/" + kofi + "/fee"), admin, "{\"via\":\"cash\"}"), 200);
		assertThat(person(marked, kofi).get("feePaidVia").asString()).isEqualTo("cash");
		assertThat(person(marked, kofi).get("feePaidAt").asString()).isNotBlank();
		ok(send(put("/events/" + eventId + "/people/" + kofi + "/fee"), admin, "{\"via\":\"bitcoin\"}"), 422);

		// The player sees their own (not paid), and nobody else's.
		var asPlayer = ok(send(get("/events/" + eventId), player, null), 200);
		assertThat(person(asPlayer, kofi).has("feePaidVia")).isFalse();
		ok(send(put("/events/" + eventId + "/people/" + kofi + "/fee"), player, "{\"via\":null}"), 404);

		// Taken back; and a details edit that leaves the fee out keeps it.
		var unmarked = ok(send(put("/events/" + eventId + "/people/" + kofi + "/fee"), admin, "{\"via\":null}"), 200);
		assertThat(person(unmarked, kofi).has("feePaidVia")).isFalse();
		var renamed = ok(send(patch("/events/" + eventId), admin, "{\"name\":\"Hillview Games Day 2030\",\"startsOn\":\"2030-06-01\"}"), 200);
		assertThat(renamed.at("/event/entryFee").asLong()).isEqualTo(1000);
		var free = ok(send(patch("/events/" + eventId), admin, "{\"name\":\"Hillview Games Day 2030\",\"startsOn\":\"2030-06-01\",\"entryFee\":0}"), 200);
		assertThat(free.at("/event").has("entryFee")).isFalse();
	}

	private static String personNamed(JsonNode event, String name) {
		for (var p : event.get("people")) {
			if (name.equals(p.get("name").asString())) {
				return p.get("id").asString();
			}
		}
		throw new AssertionError("no " + name);
	}

	private static JsonNode person(JsonNode event, String id) {
		for (var p : event.get("people")) {
			if (id.equals(p.get("id").asString())) {
				return p;
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
