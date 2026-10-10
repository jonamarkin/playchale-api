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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * When and where on the day: a coordinator plans a game's times across its courts or tables, every
 * match gets its slot (the later rounds too, as they're made), one can be moved by hand, and the
 * people in it hear.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EventScheduleApiTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper json;

	private Cookie admin;

	private Cookie player;

	private String eventId;

	private String boardToken;

	@BeforeEach
	void setUp() throws Exception {
		jdbc.sql("""
				TRUNCATE users, sign_in_codes, sessions, organisations, organisation_memberships, organisation_invitations,
				         events, notifications, audit_events CASCADE
				""").update();
		admin = TestSignIn.as(mvc, "024 455 5601");
		player = TestSignIn.as(mvc, "024 455 5602");
		var workspace = ok(send(post("/organisations"), admin, """
				{"name":"Hillview Chapel","slug":"hillview-chapel","country":"GH","primaryColour":"#0c3a3a"}
				"""), 201);
		var event = ok(send(post("/organisations/" + workspace.at("/organisation/id").asString() + "/events"), admin, """
				{"name":"Hillview Games Day","startsOn":"2030-06-01"}
				"""), 201);
		eventId = event.at("/event/id").asString();
		var board = event.at("/links/boardUrl").asString();
		boardToken = board.substring(board.lastIndexOf('/') + 1);
		var code = event.at("/links/joinUrl").asString().split("code=")[1];
		ok(send(post("/events/join/" + code), player, "{\"gameIds\":[]}"), 200);
	}

	@Test
	void aPlanGivesEachMatchATimeAndAPlaceAndTheNextRoundGetsItsOwn() throws Exception {
		var game = addGame("{\"discipline\":\"table-tennis\"}");
		enter(game, 4);
		var drawn = game(ok(send(post(path(game) + "/draw"), admin, null), 200), game);
		assertThat(drawn.get("matches").get(0).has("startsAt")).isFalse();

		// Two tables at once, twenty minutes a match; the same table typed twice is one table.
		var planned = game(plan(game, "2030-06-01T09:00:00Z", 20, "\"Table 1\",\"Table 2\",\" table 1 \"", 200), game);
		assertThat(planned.get("matchMinutes").asInt()).isEqualTo(20);
		assertThat(planned.get("locations").size()).isEqualTo(2);
		assertThat(planned.get("location").asString()).isEqualTo("Table 1, Table 2");
		var semis = round(planned, 1);
		assertThat(semis).extracting(m -> m.get("startsAt").asString()).containsOnly("2030-06-01T09:00:00Z");
		assertThat(semis).extracting(m -> m.get("location").asString()).containsExactlyInAnyOrder("Table 1", "Table 2");

		// The player with an account hears their first match, its time and its table.
		var heard = jdbc.sql("SELECT title || ' / ' || body FROM notifications WHERE kind = 'event' AND title LIKE '%times are out'")
			.query(String.class).list();
		assertThat(heard).hasSize(1);
		assertThat(heard.getFirst()).startsWith("Table tennis: the times are out / First up: against ").contains("9:00 am, Table");

		// One semi moved by hand stays put when the other's result goes in.
		var moved = semis.get(1);
		ok(send(put("/events/" + eventId + "/matches/" + moved.get("id").asString() + "/slot"), admin,
				"{\"startsAt\":\"2030-06-01T10:00:00Z\",\"location\":\"Court 9\"}"), 200);
		var event = record(semis.get(0));
		var stillMoved = find(game(event, game).get("matches"), moved.get("id").asString());
		assertThat(stillMoved.get("startsAt").asString()).isEqualTo("2030-06-01T10:00:00Z");
		assertThat(stillMoved.get("location").asString()).isEqualTo("Court 9");

		// The final, made once the semis are in, lands where the plan said: after them, on the first table.
		event = record(stillMoved);
		var last = round(game(event, game), 2).getFirst();
		assertThat(last.get("startsAt").asString()).isEqualTo("2030-06-01T09:20:00Z");
		assertThat(last.get("location").asString()).isEqualTo("Table 1");

		// What makes no sense is refused; only those running the game plan it.
		plan(game, "2030-06-01T09:00:00Z", 2, "\"Table 1\"", 422);
		plan(game, "2030-06-01T09:00:00Z", 20, "\"  \"", 422);
		var refused = send(put(path(game) + "/plan"), player, "{\"startsAt\":\"2030-06-01T09:00:00Z\",\"minutes\":20,\"locations\":[\"T\"]}")
			.andReturn().getResponse().getStatus();
		assertThat(refused).isIn(403, 404);
	}

	@Test
	void heatsTakeTurnsOnOneBoardAndTheFinalComesAfterThem() throws Exception {
		var game = addGame("{\"discipline\":\"ludo\",\"heatSize\":4,\"advancePerHeat\":2}");
		enter(game, 8);
		// Planned before the draw: the heats get their times when it's made.
		plan(game, "2030-06-01T10:00:00Z", 15, "\"Board 1\"", 200);
		var heats = game(ok(send(post(path(game) + "/draw"), admin, null), 200), game).get("heats");
		var times = new ArrayList<String>();
		heats.forEach(h -> times.add(h.get("startsAt").asString()));
		assertThat(times).containsExactly("2030-06-01T10:00:00Z", "2030-06-01T10:15:00Z");

		// The board shows what's next, soonest first, with where.
		var board = ok(mvc.perform(get("/boards/" + boardToken)), 200);
		var next = board.at("/games/0/next/0");
		assertThat(next.get("line").asString()).startsWith("Heat 1: ");
		assertThat(next.get("location").asString()).isEqualTo("Board 1");

		placings(heats.get(0), List.of(1, 2, 3, 4));
		var event = ok(placings(heats.get(1), List.of(4, 3, 2, 1)), 200);
		var last = game(event, game).get("heats");
		JsonNode theFinal = null;
		for (var h : last) {
			if ("final".equals(h.get("stage").asString())) {
				theFinal = h;
			}
		}
		assertThat(theFinal).isNotNull();
		assertThat(theFinal.get("startsAt").asString()).isEqualTo("2030-06-01T10:30:00Z");

		// The final moved by hand: everyone in it with an account would hear; nobody here has one.
		var movedFinal = game(ok(send(put("/events/" + eventId + "/heats/" + theFinal.get("id").asString() + "/slot"), admin,
				"{\"startsAt\":\"2030-06-01T11:00:00Z\",\"location\":\"Main hall\"}"), 200), game);
		assertThat(movedFinal.toString()).contains("Main hall");
	}

	private JsonNode plan(String game, String startsAt, int minutes, String locations, int status) throws Exception {
		return ok(send(put(path(game) + "/plan"), admin,
				"{\"startsAt\":\"%s\",\"minutes\":%d,\"locations\":[%s]}".formatted(startsAt, minutes, locations)), status);
	}

	private JsonNode record(JsonNode match) throws Exception {
		return ok(send(put("/events/" + eventId + "/matches/" + match.get("id").asString() + "/result"), admin,
				"{\"sets\":[{\"home\":11,\"away\":5},{\"home\":11,\"away\":8}]}"), 200);
	}

	private ResultActions placings(JsonNode heat, List<Integer> places) throws Exception {
		var body = new StringBuilder("{\"placings\":[");
		var lanes = heat.get("lanes");
		for (int i = 0; i < lanes.size(); i++) {
			body.append(i == 0 ? "" : ",").append("{\"entryId\":\"%s\",\"place\":%d}".formatted(lanes.get(i).get("entryId").asString(), places.get(i)));
		}
		return send(put("/events/" + eventId + "/heats/" + heat.get("id").asString() + "/placings"), admin, body.append("]}").toString());
	}

	private String addGame(String body) throws Exception {
		var games = ok(send(post("/events/" + eventId + "/games"), admin, body), 200).get("games");
		return games.get(games.size() - 1).get("id").asString();
	}

	/** The player with an account, and {@code count - 1} others typed in, each entered on their own. */
	private void enter(String game, int count) throws Exception {
		var people = new StringBuilder("{\"people\":[");
		var before = ok(send(get("/events/" + eventId), admin, null), 200).get("people").size();
		for (int i = 1; i < count; i++) {
			people.append(i == 1 ? "" : ",").append("{\"name\":\"Player %d-%d\"}".formatted(before, i));
		}
		var event = ok(send(post("/events/" + eventId + "/people"), admin, people.append("]}").toString()), 200);
		var account = jdbc.sql("SELECT id::text FROM event_people WHERE user_id IS NOT NULL").query(String.class).single();
		for (var person : event.get("people")) {
			var id = person.get("id").asString();
			if (person.get("name").asString().startsWith("Player %d-".formatted(before)) || id.equals(account)) {
				ok(send(post(path(game) + "/entries"), admin, "{\"personIds\":[\"%s\"]}".formatted(id)), 200);
			}
		}
	}

	private String path(String game) {
		return "/events/" + eventId + "/games/" + game;
	}

	private static JsonNode game(JsonNode event, String id) {
		return find(event.get("games"), id);
	}

	private static JsonNode find(JsonNode list, String id) {
		for (var n : list) {
			if (id.equals(n.get("id").asString())) {
				return n;
			}
		}
		throw new AssertionError("no " + id);
	}

	private static List<JsonNode> round(JsonNode game, int round) {
		var matches = new ArrayList<JsonNode>();
		for (var m : game.get("matches")) {
			if (m.get("round").asInt() == round && !m.get("thirdPlace").asBoolean()) {
				matches.add(m);
			}
		}
		return matches;
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
