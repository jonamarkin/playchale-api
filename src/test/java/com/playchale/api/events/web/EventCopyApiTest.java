package com.playchale.api.events.web;

import java.time.Instant;
import java.util.UUID;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Running an event again next year: the groups, games and settings come across, the results and
 * links don't, and people come only as names, if asked for.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EventCopyApiTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper json;

	private Cookie admin;

	private Cookie player;

	private JsonNode event;

	private String eventId;

	@BeforeEach
	void setUp() throws Exception {
		jdbc.sql("""
				TRUNCATE users, sign_in_codes, sessions, organisations, organisation_memberships, organisation_invitations,
				         events, notifications, audit_events CASCADE
				""").update();
		admin = TestSignIn.as(mvc, "024 455 5801");
		player = TestSignIn.as(mvc, "024 455 5802");
		var workspace = ok(send(post("/organisations"), admin, """
				{"name":"Hillview Chapel","slug":"hillview-chapel","country":"GH","primaryColour":"#0c3a3a"}
				"""), 201);
		event = ok(send(post("/organisations/" + workspace.at("/organisation/id").asString() + "/events"), admin, """
				{"name":"Hillview Games Day 2030","startsOn":"2030-06-01","venue":{"name":"Chapel grounds","area":"Adenta"},
				 "groups":[{"name":"Joy Fellowship"},{"name":"Hope Fellowship"}],"placingPoints":[7,4,2],"entryFee":1000}
				"""), 201);
		eventId = event.at("/event/id").asString();
		var code = event.at("/links/joinUrl").asString().split("code=")[1];
		ok(send(post("/events/join/" + code), player, "{\"gameIds\":[],\"groupId\":\"%s\"}".formatted(event.at("/groups/0/id").asString())), 200);
		ok(send(post("/events/" + eventId + "/people"), admin,
				"{\"people\":[{\"name\":\"Kofi Mensah\",\"groupId\":\"%s\"}]}".formatted(event.at("/groups/1/id").asString())), 200);
	}

	@Test
	void theGamesGroupsAndSettingsComeAcrossButNotWhatWasPlayed() throws Exception {
		var games = ok(send(post("/events/" + eventId + "/games"), admin,
				"{\"discipline\":\"table-tennis\",\"format\":\"pools\",\"poolSize\":3,\"advancePerPool\":1,\"category\":\"Open\"}"), 200).get("games");
		var game = games.get(0).get("id").asString();
		ok(send(put("/events/" + eventId + "/games/" + game + "/plan"), admin,
				"{\"startsAt\":\"2030-06-01T09:00:00Z\",\"minutes\":15,\"locations\":[\"Table 1\",\"Table 2\"]}"), 200);
		var adminId = jdbc.sql("SELECT id FROM users WHERE phone = '+233244555801'").query(UUID.class).single();
		var outsider = jdbc.sql("SELECT id FROM users WHERE phone = '+233244555802'").query(UUID.class).single();
		ok(send(put("/events/" + eventId + "/games/" + game + "/coordinators"), admin, "{\"userIds\":[\"%s\"]}".formatted(adminId)), 200);
		// Someone who coordinated last year and has since left the workspace.
		jdbc.sql("INSERT INTO event_game_coordinators (game_id, user_id, created_at) VALUES (:game, :user, :now)")
			.param("game", UUID.fromString(game)).param("user", outsider).param("now", java.sql.Timestamp.from(Instant.now())).update();
		ok(send(post("/events/" + eventId + "/announcements"), admin, "{\"body\":\"Water by the gate\"}"), 200);

		var copy = ok(send(post("/events/" + eventId + "/copy"), admin,
				"{\"name\":\"Hillview Games Day 2031\",\"startsOn\":\"2031-05-31\",\"people\":true}"), 201);
		assertThat(copy.at("/event/id").asString()).isNotEqualTo(eventId);
		assertThat(copy.at("/event/name").asString()).isEqualTo("Hillview Games Day 2031");
		assertThat(copy.at("/event/endsOn").asString()).isEqualTo("2031-05-31");
		assertThat(copy.at("/event/status").asString()).isEqualTo("open");
		assertThat(copy.at("/event/entryFee").asLong()).isEqualTo(1000);
		assertThat(copy.at("/event/venue/name").asString()).isEqualTo("Chapel grounds");
		assertThat(copy.at("/event/placingPoints").toString()).isEqualTo("[7,4,2]");
		assertThat(copy.at("/links/joinUrl").asString()).isNotEqualTo(event.at("/links/joinUrl").asString());
		assertThat(copy.get("groups")).extracting(g -> g.get("name").asString()).containsExactly("Joy Fellowship", "Hope Fellowship");
		assertThat(copy.get("announcements").size()).isZero();

		var copied = copy.at("/games/0");
		assertThat(copied.get("name").asString()).isEqualTo("Table tennis");
		assertThat(copied.get("format").asString()).isEqualTo("pools");
		assertThat(copied.get("poolSize").asInt()).isEqualTo(3);
		assertThat(copied.get("status").asString()).isEqualTo("open");
		assertThat(copied.get("entries").size()).isZero();
		// The plan for the day moves to the new day; only coordinators still in the workspace come along.
		assertThat(copied.get("startsAt").asString()).isEqualTo("2031-05-31T09:00:00Z");
		assertThat(copied.get("locations").size()).isEqualTo(2);
		assertThat(copied.get("coordinators")).extracting(c -> c.get("userId").asString()).containsExactly(adminId.toString());

		// People come as names, in their groups, and nobody's account comes with them.
		assertThat(copy.get("people")).extracting(p -> p.get("name").asString()).containsExactlyInAnyOrder("Kofi Mensah",
				event.at("/people").size() > 0 ? jdbc.sql("SELECT display_name FROM event_people WHERE user_id IS NOT NULL").query(String.class).single() : "");
		assertThat(copy.get("people")).allSatisfy(p -> assertThat(p.has("userId")).isFalse());
		var hope = copy.at("/groups/1/id").asString();
		assertThat(copy.get("people")).anySatisfy(p -> {
			assertThat(p.get("name").asString()).isEqualTo("Kofi Mensah");
			assertThat(p.get("groupId").asString()).isEqualTo(hope);
		});
	}

	@Test
	void withoutThePeopleItStartsEmptyAndOnlyAnAdminCopies() throws Exception {
		var copy = ok(send(post("/events/" + eventId + "/copy"), admin, "{\"name\":\"Next year\",\"startsOn\":\"2031-06-07\"}"), 201);
		assertThat(copy.get("people").size()).isZero();
		ok(send(post("/events/" + eventId + "/copy"), admin, "{\"name\":\" \",\"startsOn\":\"2031-06-07\"}"), 422);
		var refused = send(post("/events/" + eventId + "/copy"), player, "{\"name\":\"Mine\",\"startsOn\":\"2031-06-07\"}").andReturn()
			.getResponse().getStatus();
		assertThat(refused).isIn(403, 404);
		// The original is untouched.
		var original = ok(send(get("/events/" + eventId), admin, null), 200);
		assertThat(original.get("people").size()).isEqualTo(2);
		ok(send(patch("/events/" + eventId), admin, "{\"name\":\"Hillview Games Day 2030\",\"startsOn\":\"2030-06-01\"}"), 200);
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
