package com.playchale.api.events.web;

import java.util.ArrayList;
import java.util.List;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Pools, then a knockout: everyone plays their pool, the best of each go through, a pool's results
 * can be put right until the knockout starts, and the knockout decides the game.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EventPoolsApiTest {

	private static final String WIN = "{\"sets\":[{\"home\":11,\"away\":5},{\"home\":11,\"away\":8}]}";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper json;

	private Cookie admin;

	private String eventId;

	@BeforeEach
	void setUp() throws Exception {
		jdbc.sql("""
				TRUNCATE users, sign_in_codes, sessions, organisations, organisation_memberships, organisation_invitations,
				         events, notifications, audit_events CASCADE
				""").update();
		admin = TestSignIn.as(mvc, "024 455 5701");
		var workspace = ok(send(post("/organisations"), admin, """
				{"name":"Hillview Chapel","slug":"hillview-chapel","country":"GH","primaryColour":"#0c3a3a"}
				"""), 201);
		eventId = ok(send(post("/organisations/" + workspace.at("/organisation/id").asString() + "/events"), admin, """
				{"name":"Hillview Games Day","startsOn":"2030-06-01"}
				"""), 201).at("/event/id").asString();
	}

	@Test
	void theBestOfEachPoolGoThroughToAKnockoutThatDecidesIt() throws Exception {
		var game = addGame("{\"discipline\":\"table-tennis\",\"format\":\"pools\",\"poolSize\":3,\"advancePerPool\":2}");
		enter(game, 6);
		var drawn = game(ok(send(post(path(game) + "/draw"), admin, null), 200), game);
		assertThat(drawn.get("poolSize").asInt()).isEqualTo(3);
		assertThat(drawn.get("pools").size()).isEqualTo(2);
		assertThat(drawn.at("/pools/0/table").size()).isEqualTo(3);
		// Everyone plays everyone in their pool: three matches in each pool of three, and no knockout yet.
		assertThat(pooled(drawn)).hasSize(6);
		assertThat(knockout(drawn)).isEmpty();

		// The times: three rounds of the pools side by side on two tables, then the semis.
		var planned = game(ok(send(put(path(game) + "/plan"), admin,
				"{\"startsAt\":\"2030-06-01T09:00:00Z\",\"minutes\":20,\"locations\":[\"T1\",\"T2\"]}"), 200), game);
		assertThat(pooled(planned)).extracting(m -> m.get("startsAt").asString())
			.containsOnly("2030-06-01T09:00:00Z", "2030-06-01T09:20:00Z", "2030-06-01T09:40:00Z");

		JsonNode event = null;
		for (var m : pooled(planned)) {
			event = record(m, WIN, 200);
		}
		var semis = knockout(game(event, game));
		assertThat(semis).hasSize(2);
		assertThat(semis).extracting(m -> m.get("startsAt").asString()).containsOnly("2030-06-01T10:00:00Z");
		// A winner meets the other pool's runner-up.
		var poolOf = new java.util.HashMap<String, Integer>();
		game(event, game).get("entries").forEach(e -> poolOf.put(e.get("id").asString(), e.get("pool").asInt()));
		assertThat(semis).allSatisfy(m -> assertThat(poolOf.get(m.get("homeEntryId").asString()))
			.isNotEqualTo(poolOf.get(m.get("awayEntryId").asString())));

		// A pool result can be put right until the knockout starts; taking one back takes the knockout with it.
		var firstPoolMatch = pooled(game(event, game)).getFirst();
		event = ok(send(delete("/events/" + eventId + "/matches/" + firstPoolMatch.get("id").asString() + "/result"), admin, null), 200);
		assertThat(knockout(game(event, game))).isEmpty();
		event = record(firstPoolMatch, WIN, 200);
		semis = knockout(game(event, game));
		event = record(semis.get(0), WIN, 200);
		record(firstPoolMatch, "{\"sets\":[{\"home\":5,\"away\":11},{\"home\":8,\"away\":11}]}", 409);

		event = record(knockout(game(event, game)).get(1), WIN, 200);
		var last = knockout(game(event, game)).stream().filter(m -> m.get("round").asInt() == 2).findFirst().orElseThrow();
		event = record(last, WIN, 200);
		var done = game(event, game);
		assertThat(done.get("status").asString()).isEqualTo("finished");
		assertThat(done.get("places").size()).isEqualTo(2);
		assertThat(done.get("places").get(0).asString()).isEqualTo(last.get("homeEntryId").asString());
	}

	@Test
	void poolsThatWouldLeaveSomeoneOnTheirOwnAreRefused() throws Exception {
		var game = addGame("{\"discipline\":\"chess\",\"format\":\"pools\",\"poolSize\":2,\"advancePerPool\":1}");
		enter(game, 3);
		var refused = send(post(path(game) + "/draw"), admin, null).andReturn().getResponse();
		assertThat(refused.getStatus()).isEqualTo(422);
		assertThat(refused.getContentAsString()).contains("on their own");
		// Taking a pools draw back clears the pools too.
		var ok = addGame("{\"discipline\":\"chess\",\"format\":\"pools\",\"poolSize\":2,\"advancePerPool\":1}");
		enter(ok, 4);
		ok(send(post(path(ok) + "/draw"), admin, null), 200);
		var undrawn = game(ok(send(delete(path(ok) + "/draw"), admin, null), 200), ok);
		assertThat(undrawn.has("pools") && undrawn.get("pools").size() > 0).isFalse();
		assertThat(undrawn.get("matches").size()).isZero();
	}

	private JsonNode record(JsonNode match, String body, int expected) throws Exception {
		var response = send(put("/events/" + eventId + "/matches/" + match.get("id").asString() + "/result"), admin, body).andReturn()
			.getResponse();
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
		return expected == 200 ? json.readTree(response.getContentAsString()) : null;
	}

	private static List<JsonNode> pooled(JsonNode game) {
		var list = new ArrayList<JsonNode>();
		game.get("matches").forEach(m -> {
			if (m.has("pool")) {
				list.add(m);
			}
		});
		return list;
	}

	private static List<JsonNode> knockout(JsonNode game) {
		var list = new ArrayList<JsonNode>();
		game.get("matches").forEach(m -> {
			if (!m.has("pool")) {
				list.add(m);
			}
		});
		return list;
	}

	private String addGame(String body) throws Exception {
		var games = ok(send(post("/events/" + eventId + "/games"), admin, body), 200).get("games");
		return games.get(games.size() - 1).get("id").asString();
	}

	private void enter(String game, int count) throws Exception {
		var people = new StringBuilder("{\"people\":[");
		var before = ok(send(get("/events/" + eventId), admin, null), 200).get("people").size();
		for (int i = 0; i < count; i++) {
			people.append(i == 0 ? "" : ",").append("{\"name\":\"Player %d-%d\"}".formatted(before, i));
		}
		var event = ok(send(post("/events/" + eventId + "/people"), admin, people.append("]}").toString()), 200);
		for (var person : event.get("people")) {
			if (person.get("name").asString().startsWith("Player %d-".formatted(before))) {
				ok(send(post(path(game) + "/entries"), admin, "{\"personIds\":[\"%s\"]}".formatted(person.get("id").asString())), 200);
			}
		}
	}

	private String path(String game) {
		return "/events/" + eventId + "/games/" + game;
	}

	private static JsonNode game(JsonNode event, String id) {
		for (var n : event.get("games")) {
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
