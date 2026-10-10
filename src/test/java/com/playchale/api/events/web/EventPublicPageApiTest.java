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
 * An event's public page: off until its admins turn it on, open to anyone with the link while it's
 * on, and showing the games and results by name only: nobody's account, no money, no links.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EventPublicPageApiTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper json;

	private Cookie admin;

	private Cookie player;

	private String eventId;

	@BeforeEach
	void setUp() throws Exception {
		jdbc.sql("""
				TRUNCATE users, sign_in_codes, sessions, organisations, organisation_memberships, organisation_invitations,
				         events, notifications, audit_events CASCADE
				""").update();
		admin = TestSignIn.as(mvc, "024 455 5901");
		player = TestSignIn.as(mvc, "024 455 5902");
		var workspace = ok(send(post("/organisations"), admin, """
				{"name":"Hillview Chapel","slug":"hillview-chapel","country":"GH","primaryColour":"#0c3a3a"}
				"""), 201);
		var event = ok(send(post("/organisations/" + workspace.at("/organisation/id").asString() + "/events"), admin, """
				{"name":"Hillview Games Day 2030!","startsOn":"2030-06-01","groups":[{"name":"Joy Fellowship"}],"entryFee":1000}
				"""), 201);
		eventId = event.at("/event/id").asString();
		var code = event.at("/links/joinUrl").asString().split("code=")[1];
		ok(send(post("/events/join/" + code), player, "{\"gameIds\":[],\"groupId\":\"%s\"}".formatted(event.at("/groups/0/id").asString())), 200);
		var game = ok(send(post("/events/" + eventId + "/games"), admin, "{\"discipline\":\"oware\"}"), 200).at("/games/0/id").asString();
		var people = ok(send(post("/events/" + eventId + "/people"), admin, "{\"people\":[{\"name\":\"Kofi Mensah\"}]}"), 200).get("people");
		for (var p : people) {
			ok(send(post("/events/" + eventId + "/games/" + game + "/entries"), admin, "{\"personIds\":[\"%s\"]}".formatted(p.get("id").asString())), 200);
		}
	}

	@Test
	void onlyWhileItsOnAnyoneWithTheLinkSeesTheGamesByNameOnly() throws Exception {
		assertThat(ok(send(get("/events/" + eventId), admin, null), 200).at("/event").has("publicPath")).isFalse();
		var on = ok(send(put("/events/" + eventId + "/public-page"), admin, "{\"on\":true}"), 200);
		var path = on.at("/event/publicPath").asString();
		assertThat(path).matches("/e/hillview-games-day-2030-[a-z0-9]{6}");
		// Everyone in the event can see the link to pass it on.
		assertThat(ok(send(get("/events/" + eventId), player, null), 200).at("/event/publicPath").asString()).isEqualTo(path);

		var page = ok(mvc.perform(get("/public/events/" + path.substring(3))), 200);
		assertThat(page.at("/event/name").asString()).isEqualTo("Hillview Games Day 2030!");
		assertThat(page.at("/games/0/entries").size()).isEqualTo(2);
		assertThat(page.at("/games/0/entries/0/personIds").size()).isZero();
		assertThat(page.get("people").size()).isZero();
		assertThat(page.at("/event").has("entryFee")).isFalse();
		assertThat(page.has("links")).isFalse();
		assertThat(page.has("viewer")).isFalse();

		// Off: the link stops working. On again: a new one.
		ok(send(put("/events/" + eventId + "/public-page"), admin, "{\"on\":false}"), 200);
		ok(mvc.perform(get("/public/events/" + path.substring(3))), 404);
		var again = ok(send(put("/events/" + eventId + "/public-page"), admin, "{\"on\":true}"), 200).at("/event/publicPath").asString();
		assertThat(again).isNotEqualTo(path);

		var refused = send(put("/events/" + eventId + "/public-page"), player, "{\"on\":false}").andReturn().getResponse().getStatus();
		assertThat(refused).isIn(403, 404);
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
