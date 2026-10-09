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
 * A games day being played: draws, results in every way a game is scored, corrections, the overall
 * table of groups, finishing it, and the board on a screen nobody's signed in on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EventPlayApiTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper json;

	private Cookie admin;

	private String eventId;

	private String joy;

	private String hope;

	@BeforeEach
	void setUp() throws Exception {
		jdbc.sql("""
				TRUNCATE users, sign_in_codes, sessions, organisations, organisation_memberships, organisation_invitations,
				         events, notifications, audit_events CASCADE
				""").update();
		admin = TestSignIn.as(mvc, "024 455 5301");
		var workspace = ok(send(post("/organisations"), admin, """
				{"name":"Hillview Chapel","slug":"hillview-chapel","country":"GH","primaryColour":"#0c3a3a"}
				"""), 201);
		var event = ok(send(post("/organisations/" + workspace.at("/organisation/id").asString() + "/events"), admin, """
				{"name":"Hillview Games Day","startsOn":"2030-06-01","groups":[{"name":"Joy Fellowship"},{"name":"Hope Fellowship"}]}
				"""), 201);
		eventId = event.at("/event/id").asString();
		joy = event.at("/groups/0/id").asString();
		hope = event.at("/groups/1/id").asString();
	}

	@Test
	void aKnockoutWithByesPlaysToAFinalAndAMatchForThird() throws Exception {
		var game = addGame("{\"discipline\":\"table-tennis\",\"thirdPlace\":true}");
		enterSingles(game, 5);
		var event = ok(send(post(path(game) + "/draw"), admin, null), 200);
		var drawn = game(event, game);
		assertThat(drawn.get("status").asString()).isEqualTo("drawn");
		// Five in a bracket of eight: four first-round slots, three of them byes.
		assertThat(round(drawn, 1)).hasSize(4);
		assertThat(round(drawn, 1).stream().filter(m -> !m.has("awayEntryId")).count()).isEqualTo(3);

		event = playOut(game, "{\"sets\":[{\"home\":11,\"away\":5},{\"home\":11,\"away\":8}]}");
		var done = game(event, game);
		assertThat(done.get("status").asString()).isEqualTo("finished");
		assertThat(done.get("places").size()).isEqualTo(4);
		assertThat(done.get("matches").findValues("thirdPlace").stream().anyMatch(JsonNode::asBoolean)).isTrue();
	}

	@Test
	void aResultCantChangeOnceTheNextRoundIsPlayed() throws Exception {
		var game = addGame("{\"discipline\":\"draughts\"}");
		enterSingles(game, 4);
		ok(send(post(path(game) + "/draw"), admin, null), 200);
		// The draw can be taken back and made again while nothing's played.
		ok(send(delete(path(game) + "/draw"), admin, null), 200);
		var event = ok(send(post(path(game) + "/draw"), admin, null), 200);
		var semis = round(game(event, game), 1);
		record(semis.get(0), "{\"winner\":\"home\"}", 200);
		assertThat(status(send(delete(path(game) + "/draw"), admin, null))).isEqualTo(409);
		// A draw in a knockout is refused: someone has to go through.
		record(semis.get(1), "{\"winner\":\"draw\"}", 422);
		event = record(semis.get(1), "{\"winner\":\"away\"}", 200);
		var last = round(game(event, game), 2).getFirst();
		assertThat(last.has("homeEntryId") && last.has("awayEntryId")).isTrue();
		// Correcting a semi before the final is played moves the final's side with it.
		event = record(semis.get(0), "{\"winner\":\"away\"}", 200);
		last = round(game(event, game), 2).getFirst();
		assertThat(last.get("homeEntryId").asString()).isEqualTo(semis.get(0).get("awayEntryId").asString());
		record(last, "{\"winner\":\"home\"}", 200);
		record(semis.get(0), "{\"winner\":\"home\"}", 409);
		assertThat(status(send(delete("/events/" + eventId + "/matches/" + semis.get(0).get("id").asString() + "/result"), admin, null)))
			.isEqualTo(409);
	}

	@Test
	void aLeagueAddsUpWinsAndDrawsIntoATable() throws Exception {
		var game = addGame("{\"discipline\":\"oware\",\"format\":\"league\"}");
		enterSingles(game, 4);
		var event = ok(send(post(path(game) + "/draw"), admin, null), 200);
		assertThat(game(event, game).get("matches").size()).isEqualTo(6);
		var outcomes = List.of("home", "draw", "away", "home", "home", "draw");
		var matches = game(event, game).get("matches");
		for (int i = 0; i < 6; i++) {
			event = record(matches.get(i), "{\"winner\":\"%s\"}".formatted(outcomes.get(i)), 200);
		}
		var done = game(event, game);
		assertThat(done.get("status").asString()).isEqualTo("finished");
		var table = done.get("table");
		var points = new ArrayList<Integer>();
		table.forEach(r -> points.add(r.get("points").asInt()));
		assertThat(points).isSortedAccordingTo((a, b) -> b - a);
		assertThat(points.stream().mapToInt(Integer::intValue).sum()).isEqualTo(4 * 3 + 2 * 2);
		assertThat(done.get("places").get(0).asString()).isEqualTo(table.get(0).get("entryId").asString());
	}

	@Test
	void ludoHeatsFeedAFinalAndThePlacesGoToTheOverallTable() throws Exception {
		var game = addGame("{\"discipline\":\"ludo\"}");
		enterSingles(game, 8);
		var event = ok(send(post(path(game) + "/draw"), admin, null), 200);
		var heats = game(event, game).get("heats");
		assertThat(heats.size()).isEqualTo(2);
		assertThat(heats.get(0).get("stage").asString()).isEqualTo("heat");

		// Everyone placed once: a place twice is refused.
		var first = heats.get(0);
		assertThat(status(placings(first, List.of(1, 1, 2, 3)))).isEqualTo(422);
		ok(placings(first, List.of(2, 1, 4, 3)), 200);
		event = ok(placings(heats.get(1), List.of(1, 2, 3, 4)), 200);
		var finalHeat = game(event, game).get("heats").get(2);
		assertThat(finalHeat.get("stage").asString()).isEqualTo("final");
		assertThat(finalHeat.get("lanes").size()).isEqualTo(2);
		// The winner of heat 1 came 2nd in lane 2.
		assertThat(finalHeat.at("/lanes/0/entryId").asString()).isEqualTo(first.at("/lanes/1/entryId").asString());

		event = ok(placings(finalHeat, List.of(1, 2)), 200);
		var done = game(event, game);
		assertThat(done.get("status").asString()).isEqualTo("finished");
		var winner = done.get("places").get(0).asString();
		var winnersGroup = find(done.get("entries"), winner).get("groupId").asString();
		var top = event.get("table").get(0);
		assertThat(top.get("groupId").asString()).isEqualTo(winnersGroup);
		assertThat(top.get("gold").asInt()).isEqualTo(1);
		// 5 for 1st, and 3 more if the runner-up is from the same group: the draw is random.
		var runnersUp = find(done.get("entries"), done.get("places").get(1).asString()).get("groupId").asString();
		assertThat(top.get("points").asInt()).isEqualTo(runnersUp.equals(winnersGroup) ? 8 : 5);
		// A heat can't change under a final that's been run.
		assertThat(status(placings(first, List.of(1, 2, 3, 4)))).isEqualTo(409);
	}

	@Test
	void theBoardShowsTheEventSignedOutUntilItsLinkChanges() throws Exception {
		var game = addGame("{\"discipline\":\"chess\"}");
		enterSingles(game, 2);
		var event = ok(send(post(path(game) + "/draw"), admin, null), 200);
		record(game(event, game).get("matches").get(0), "{\"winner\":\"home\"}", 200);

		var boardUrl = ok(mvc.perform(get("/events/" + eventId).cookie(admin)), 200).at("/links/boardUrl").asString();
		var token = boardUrl.substring(boardUrl.lastIndexOf('/') + 1);
		var board = ok(mvc.perform(get("/boards/" + token)), 200);
		assertThat(board.get("name").asString()).isEqualTo("Hillview Games Day");
		assertThat(board.get("table").size()).isEqualTo(2);
		assertThat(board.at("/table/0/points").asInt()).isEqualTo(5);
		assertThat(board.at("/games/0/places").size()).isEqualTo(2);
		assertThat(board.at("/latest/0/summary").asString()).contains("beat");
		assertThat(board.has("people")).isFalse();

		ok(send(post("/events/" + eventId + "/board-link"), admin, null), 200);
		assertThat(mvc.perform(get("/boards/" + token)).andReturn().getResponse().getStatus()).isEqualTo(404);
	}

	@Test
	void aFinishedEventTakesNoResultsUntilItsPutBackOn() throws Exception {
		var game = addGame("{\"discipline\":\"chess\"}");
		enterSingles(game, 2);
		var event = ok(send(post(path(game) + "/draw"), admin, null), 200);
		var match = game(event, game).get("matches").get(0);
		ok(send(post("/events/" + eventId + "/finish"), admin, null), 200);
		record(match, "{\"winner\":\"home\"}", 409);
		ok(send(post("/events/" + eventId + "/reopen"), admin, null), 200);
		record(match, "{\"winner\":\"home\"}", 200);
	}

	@Test
	void playersWithAccountsHearAboutTheDrawAndTheirResults() throws Exception {
		var game = addGame("{\"discipline\":\"chess\"}");
		var player = TestSignIn.as(mvc, "024 455 5302");
		var code = ok(mvc.perform(get("/events/" + eventId).cookie(admin)), 200).at("/links/joinUrl").asString().split("code=")[1];
		ok(send(post("/events/join/" + code), player, "{\"groupId\":\"%s\",\"gameIds\":[\"%s\"]}".formatted(joy, game)), 200);
		enterSingles(game, 1);
		var event = ok(send(post(path(game) + "/draw"), admin, null), 200);
		record(game(event, game).get("matches").get(0), "{\"winner\":\"home\"}", 200);
		var titles = jdbc.sql("SELECT title FROM notifications WHERE kind = 'event' ORDER BY created_at").query(String.class).list();
		assertThat(titles).containsExactly("Chess: the draw is out", "Chess");
	}

	/* ------------------------------------------------------------------ */

	/** Plays every match that's ready until the game is decided, the home side winning each. */
	private JsonNode playOut(String game, String result) throws Exception {
		JsonNode event = ok(mvc.perform(get("/events/" + eventId).cookie(admin)), 200);
		for (int guard = 0; guard < 20; guard++) {
			var g = game(event, game);
			if ("finished".equals(g.get("status").asString())) {
				return event;
			}
			JsonNode ready = null;
			for (var m : g.get("matches")) {
				if (!m.has("recordedAt") && m.has("homeEntryId") && m.has("awayEntryId")) {
					ready = m;
					break;
				}
			}
			assertThat(ready).as("a match ready to play").isNotNull();
			event = record(ready, result, 200);
		}
		throw new AssertionError("never finished");
	}

	private JsonNode record(JsonNode match, String body, int expected) throws Exception {
		var result = send(put("/events/" + eventId + "/matches/" + match.get("id").asString() + "/result"), admin, body);
		var response = result.andReturn().getResponse();
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
		return expected == 200 ? json.readTree(response.getContentAsString()) : null;
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

	/** {@code count} people, alternately in Joy and Hope, each entered on their own. */
	private void enterSingles(String game, int count) throws Exception {
		var people = new StringBuilder("{\"people\":[");
		var before = ok(mvc.perform(get("/events/" + eventId).cookie(admin)), 200).get("people").size();
		for (int i = 0; i < count; i++) {
			people.append(i == 0 ? "" : ",").append("{\"name\":\"Player %d-%d\",\"groupId\":\"%s\"}".formatted(before, i, i % 2 == 0 ? joy : hope));
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
		game.get("matches").forEach(m -> {
			if (m.get("round").asInt() == round && !m.get("thirdPlace").asBoolean()) {
				matches.add(m);
			}
		});
		return matches;
	}

	private int status(ResultActions result) {
		return result.andReturn().getResponse().getStatus();
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
