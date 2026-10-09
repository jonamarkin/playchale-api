package com.playchale.api.events.web;

import java.util.ArrayList;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A church games day, set up and signed up for: the workspace admin makes the event and its games,
 * types in names, players join with the link, coordinators run their own games, and nobody else can
 * see any of it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EventsApiTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper json;

	/** Owns the workspace. */
	private Cookie admin;

	/** Holds an official's seat in it, and coordinates table tennis. */
	private Cookie official;

	/** Takes part with their own account. */
	private Cookie player;

	private Cookie otherPlayer;

	/** Signed in, with no part in any of this. */
	private Cookie outsider;

	private String organisationId;

	private String eventId;

	private String joy;

	private String hope;

	@BeforeEach
	void setUp() throws Exception {
		jdbc.sql("""
				TRUNCATE users, sign_in_codes, sessions, organisations, organisation_memberships, organisation_invitations,
				         events, notifications, audit_events CASCADE
				""").update();
		admin = TestSignIn.as(mvc, "024 455 5201");
		official = TestSignIn.as(mvc, "024 455 5202");
		player = TestSignIn.as(mvc, "024 455 5203");
		otherPlayer = TestSignIn.as(mvc, "024 455 5204");
		outsider = TestSignIn.as(mvc, "024 455 5205");
		name(player, "Ama Owusu");
		name(otherPlayer, "Kojo Asare");

		var workspace = ok(send(post("/organisations"), admin, """
				{"name":"Hillview Chapel","slug":"hillview-chapel","country":"GH","primaryColour":"#0c3a3a"}
				"""), 201);
		organisationId = workspace.at("/organisation/id").asString();
		var invite = ok(send(post("/organisations/" + organisationId + "/invitations"), admin, "{\"role\":\"official\"}"), 201);
		var token = invite.get("inviteUrl").asString().split("token=")[1];
		ok(send(post("/organisation-invitations/accept"), official, "{\"token\":\"%s\"}".formatted(token)), 200);

		var event = ok(send(post("/organisations/" + organisationId + "/events"), admin, """
				{"name":"Hillview Games Day","startsOn":"2030-06-01","venue":{"name":"Hillview Chapel grounds","area":"Adenta"},
				 "groups":[{"name":"Joy Fellowship"},{"name":"Hope Fellowship","colour":"#d1342f"}]}
				"""), 201);
		eventId = event.at("/event/id").asString();
		joy = event.at("/groups/0/id").asString();
		hope = event.at("/groups/1/id").asString();
	}

	@Test
	void anAdminSetsUpTheEventAndOnlyThePeopleInItCanSeeIt() throws Exception {
		var event = ok(mvc.perform(get("/events/" + eventId).cookie(admin)), 200);
		assertThat(event.at("/event/name").asString()).isEqualTo("Hillview Games Day");
		assertThat(event.at("/event/endsOn").asString()).isEqualTo("2030-06-01");
		assertThat(event.at("/event/timezone").asString()).isEqualTo("Africa/Accra");
		assertThat(event.at("/event/placingPoints").toString()).isEqualTo("[5,3,1]");
		assertThat(event.at("/groups/0/colour").asString()).startsWith("#");
		assertThat(event.at("/groups/1/colour").asString()).isEqualTo("#d1342f");
		assertThat(event.at("/viewer/role").asString()).isEqualTo("admin");
		assertThat(event.at("/links/joinUrl").asString()).startsWith("/events/join?code=");

		// An official in the workspace can look, but the links are the admins'.
		var asOfficial = ok(mvc.perform(get("/events/" + eventId).cookie(official)), 200);
		assertThat(asOfficial.at("/viewer/role").asString()).isEqualTo("staff");
		assertThat(asOfficial.has("links")).isFalse();
		send(post("/events/" + eventId + "/games"), official, "{\"discipline\":\"oware\"}").andExpect(status().isNotFound());

		mvc.perform(get("/events/" + eventId).cookie(outsider)).andExpect(status().isNotFound());
		mvc.perform(get("/organisations/" + organisationId + "/events").cookie(outsider)).andExpect(status().isNotFound());
		send(post("/organisations/" + organisationId + "/events"), outsider, "{\"name\":\"Mine now\",\"startsOn\":\"2030-06-01\"}")
			.andExpect(status().isNotFound());

		var listed = ok(mvc.perform(get("/organisations/" + organisationId + "/events").cookie(official)), 200);
		assertThat(listed.get(0).get("name").asString()).isEqualTo("Hillview Games Day");
		assertThat(ok(mvc.perform(get("/me/events").cookie(admin)), 200).size()).isEqualTo(1);
	}

	@Test
	void namesAreTypedInOrPastedWithTheirGroups() throws Exception {
		var event = ok(send(post("/events/" + eventId + "/people"), admin, """
				{"people":[{"name":"Kofi Mensah","groupId":"%s"},{"name":"  Esi   Boateng ","groupId":"%s"},{"name":"Kofi Mensah"},
				           {"name":"Yaw Darko","groupId":"%s"}]}
				""".formatted(joy, hope, hope)), 200);
		var names = new ArrayList<String>();
		event.get("people").forEach(p -> names.add(p.get("name").asString()));
		// Two Kofis is normal. The screen points it out; the server doesn't refuse it.
		assertThat(names).containsExactly("Esi Boateng", "Kofi Mensah", "Kofi Mensah", "Yaw Darko");
		send(post("/events/" + eventId + "/people"), admin, "{\"people\":[{\"name\":\"\"}]}").andExpect(status().isUnprocessableEntity());

		var esi = person(event, "Esi Boateng");
		event = ok(send(patch("/events/" + eventId + "/people/" + esi), admin, "{\"name\":\"Esi Boateng\",\"groupId\":\"%s\"}".formatted(joy)), 200);
		assertThat(find(event.get("people"), "id", esi).get("groupId").asString()).isEqualTo(joy);
	}

	@Test
	void gamesComeFromTheListOrAreMadeUp() throws Exception {
		var event = ok(send(post("/events/" + eventId + "/games"), admin, "{\"discipline\":\"table-tennis\",\"category\":\"Women\"}"), 200);
		var game = event.get("games").get(0);
		assertThat(game.get("name").asString()).isEqualTo("Table tennis");
		assertThat(game.get("category").asString()).isEqualTo("Women");
		assertThat(game.get("scoring").asString()).isEqualTo("sets");
		assertThat(game.get("status").asString()).isEqualTo("open");

		send(post("/events/" + eventId + "/games"), admin, "{\"discipline\":\"football\",\"entryKind\":\"single\"}")
			.andExpect(status().isUnprocessableEntity());
		send(post("/events/" + eventId + "/games"), admin, "{\"discipline\":\"custom\"}").andExpect(status().isUnprocessableEntity());
		event = ok(send(post("/events/" + eventId + "/games"), admin, """
				{"discipline":"custom","name":"Sack race","category":"Under 10","entryKind":"single","format":"placings","heatSize":6,"advancePerHeat":2}
				"""), 200);
		assertThat(event.get("games").get(1).get("scoring").asString()).isEqualTo("placings");

		var tableTennis = game.get("id").asString();
		event = ok(send(patch("/events/" + eventId + "/games/" + tableTennis), admin,
				"{\"discipline\":\"table-tennis\",\"category\":\"Women\",\"bestOf\":5,\"location\":\"Hall\"}"), 200);
		assertThat(event.get("games").get(0).get("bestOf").asInt()).isEqualTo(5);
		event = ok(send(delete("/events/" + eventId + "/games/" + tableTennis), admin, null), 200);
		assertThat(event.get("games").size()).isEqualTo(1);
	}

	@Test
	void playersJoinWithTheLinkAndPickTheirGames() throws Exception {
		var tableTennis = addGame("{\"discipline\":\"table-tennis\"}");
		var football = addGame("{\"discipline\":\"football\"}");
		var code = code();

		var preview = ok(mvc.perform(get("/events/join/" + code).cookie(player)), 200);
		assertThat(preview.get("name").asString()).isEqualTo("Hillview Games Day");
		assertThat(preview.get("groups").size()).isEqualTo(2);
		assertThat(preview.has("mine")).isFalse();

		send(post("/events/join/" + code), player, "{\"gameIds\":[]}").andExpect(status().isUnprocessableEntity());
		var event = ok(send(post("/events/join/" + code), player, """
				{"groupId":"%s","gameIds":["%s","%s"]}
				""".formatted(joy, tableTennis, football)), 200);
		var me = event.at("/viewer/personId").asString();
		assertThat(event.at("/viewer/role").asString()).isEqualTo("player");
		assertThat(find(event.get("people"), "id", me).get("name").asString()).isEqualTo("Ama Owusu");
		assertThat(find(event.get("people"), "id", me).get("source").asString()).isEqualTo("link");
		// Table tennis takes her straight in; football waits for a team.
		var singles = find(event.get("games"), "id", tableTennis);
		assertThat(singles.at("/entries/0/name").asString()).isEqualTo("Ama Owusu");
		assertThat(singles.at("/entries/0/groupId").asString()).isEqualTo(joy);
		assertThat(find(event.get("games"), "id", football).at("/interested/0").asString()).isEqualTo(me);

		// Joining again changes her mind: out of table tennis, into Hope.
		event = ok(send(post("/events/join/" + code), player, "{\"groupId\":\"%s\",\"gameIds\":[\"%s\"]}".formatted(hope, football)), 200);
		assertThat(find(event.get("games"), "id", tableTennis).get("entries").size()).isZero();
		assertThat(find(event.get("people"), "id", me).get("groupId").asString()).isEqualTo(hope);
		var mine = ok(mvc.perform(get("/events/join/" + code).cookie(player)), 200).get("mine");
		assertThat(mine.get("gameIds").toString()).isEqualTo("[\"%s\"]".formatted(football));

		// From the event page, one game at a time.
		event = ok(send(put("/events/" + eventId + "/games/" + tableTennis + "/me"), player, null), 200);
		assertThat(find(event.get("games"), "id", tableTennis).get("entries").size()).isEqualTo(1);
		event = ok(send(delete("/events/" + eventId + "/games/" + football + "/me"), player, null), 200);
		assertThat(find(event.get("games"), "id", football).get("interested").size()).isZero();

		// A new link stops the old one; closing sign-ups stops both.
		var fresh = ok(send(post("/events/" + eventId + "/join-link"), admin, null), 200).at("/links/joinUrl").asString().split("code=")[1];
		mvc.perform(get("/events/join/" + code).cookie(otherPlayer)).andExpect(status().isNotFound());
		ok(send(patch("/events/" + eventId), admin, "{\"name\":\"Hillview Games Day\",\"startsOn\":\"2030-06-01\",\"registrationOpen\":false}"), 200);
		send(post("/events/join/" + fresh), otherPlayer, "{\"groupId\":\"%s\",\"gameIds\":[]}".formatted(joy)).andExpect(status().isConflict());
	}

	@Test
	void aTeamPerGroupIsMadeFromThoseWhoAskedToPlay() throws Exception {
		var football = addGame("{\"discipline\":\"football\",\"category\":\"Men\"}");
		var code = code();
		ok(send(post("/events/join/" + code), player, "{\"groupId\":\"%s\",\"gameIds\":[\"%s\"]}".formatted(joy, football)), 200);
		ok(send(post("/events/join/" + code), otherPlayer, "{\"groupId\":\"%s\",\"gameIds\":[\"%s\"]}".formatted(hope, football)), 200);

		var event = ok(send(post("/events/" + eventId + "/games/" + football + "/entries/by-group"), admin, null), 200);
		var game = find(event.get("games"), "id", football);
		assertThat(game.get("entries").size()).isEqualTo(2);
		assertThat(game.at("/entries/0/name").asString()).isEqualTo("Joy Fellowship");
		assertThat(game.at("/entries/0/personIds").size()).isEqualTo(1);
		assertThat(game.get("interested").size()).isZero();
		send(post("/events/" + eventId + "/games/" + football + "/entries/by-group"), admin, null).andExpect(status().isConflict());

		// Being put in a team is worth telling them.
		var told = jdbc.sql("SELECT count(*) FROM notifications WHERE kind = 'event' AND title = 'You’re in Football'")
			.query(Integer.class).single();
		assertThat(told).isEqualTo(2);
	}

	@Test
	void aCoordinatorRunsTheirOwnGameAndNoOther() throws Exception {
		var tableTennis = addGame("{\"discipline\":\"table-tennis\",\"entryKind\":\"pair\"}");
		var oware = addGame("{\"discipline\":\"oware\"}");
		var event = ok(send(post("/events/" + eventId + "/people"), admin, """
				{"people":[{"name":"Kofi Mensah","groupId":"%s"},{"name":"Esi Boateng","groupId":"%s"},{"name":"Yaw Darko","groupId":"%s"}]}
				""".formatted(joy, joy, hope)), 200);
		var kofi = person(event, "Kofi Mensah");
		var esi = person(event, "Esi Boateng");
		var yaw = person(event, "Yaw Darko");
		var officialId = jdbc.sql("SELECT user_id FROM organisation_memberships WHERE role = 'official'").query(String.class).single();
		var outsiderId = jdbc.sql("SELECT id FROM users WHERE phone LIKE '%5205'").query(String.class).single();

		send(put("/events/" + eventId + "/games/" + tableTennis + "/coordinators"), admin, "{\"userIds\":[\"%s\"]}".formatted(outsiderId))
			.andExpect(status().isUnprocessableEntity());
		ok(send(put("/events/" + eventId + "/games/" + tableTennis + "/coordinators"), admin, "{\"userIds\":[\"%s\"]}".formatted(officialId)), 200);
		assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE kind = 'event' AND title = 'You’re running Table tennis'")
			.query(Integer.class).single()).isEqualTo(1);

		event = ok(send(post("/events/" + eventId + "/games/" + tableTennis + "/entries"), official,
				"{\"personIds\":[\"%s\",\"%s\"]}".formatted(kofi, esi)), 200);
		var pair = find(event.get("games"), "id", tableTennis).at("/entries/0");
		assertThat(pair.get("name").asString()).isEqualTo("Kofi Mensah & Esi Boateng");
		assertThat(pair.get("groupId").asString()).isEqualTo(joy);
		assertThat(event.at("/viewer/coordinates/0").asString()).isEqualTo(tableTennis);

		// Kofi can't be in two pairs, and a pair is two.
		send(post("/events/" + eventId + "/games/" + tableTennis + "/entries"), official, "{\"personIds\":[\"%s\",\"%s\"]}".formatted(kofi, yaw))
			.andExpect(status().isConflict());
		send(post("/events/" + eventId + "/games/" + tableTennis + "/entries"), official, "{\"personIds\":[\"%s\"]}".formatted(yaw))
			.andExpect(status().isUnprocessableEntity());
		// Oware isn't theirs.
		send(post("/events/" + eventId + "/games/" + oware + "/entries"), official, "{\"personIds\":[\"%s\"]}".formatted(yaw))
			.andExpect(status().isNotFound());

		// Taking someone out of the event takes them out of their single games; a pair keeps going without them.
		ok(send(post("/events/" + eventId + "/games/" + oware + "/entries"), admin, "{\"personIds\":[\"%s\"]}".formatted(kofi)), 200);
		event = ok(send(delete("/events/" + eventId + "/people/" + kofi), admin, null), 200);
		assertThat(find(event.get("games"), "id", oware).get("entries").size()).isZero();
		assertThat(find(event.get("games"), "id", tableTennis).at("/entries/0/personIds").size()).isEqualTo(1);
	}

	@Test
	void aPlayerWhoDeletesTheirAccountLeavesTheirPlaceButNotTheirName() throws Exception {
		var oware = addGame("{\"discipline\":\"oware\"}");
		ok(send(post("/events/join/" + code()), player, "{\"groupId\":\"%s\",\"gameIds\":[\"%s\"]}".formatted(joy, oware)), 200);
		mvc.perform(delete("/me").cookie(player)).andExpect(status().is2xxSuccessful());
		var event = ok(mvc.perform(get("/events/" + eventId).cookie(admin)), 200);
		assertThat(event.at("/people/0/name").asString()).isEqualTo("Former participant");
		assertThat(event.at("/people/0").has("userId")).isFalse();
		assertThat(find(event.get("games"), "id", oware).at("/entries/0/name").asString()).isEqualTo("Former participant");
	}

	/* ------------------------------------------------------------------ */

	private String addGame(String body) throws Exception {
		var games = ok(send(post("/events/" + eventId + "/games"), admin, body), 200).get("games");
		return games.get(games.size() - 1).get("id").asString();
	}

	private String code() throws Exception {
		return ok(mvc.perform(get("/events/" + eventId).cookie(admin)), 200).at("/links/joinUrl").asString().split("code=")[1];
	}

	private void name(Cookie who, String name) throws Exception {
		send(patch("/me"), who, "{\"name\":\"%s\"}".formatted(name)).andExpect(status().isOk());
	}

	private static String person(JsonNode event, String name) {
		return find(event.get("people"), "name", name).get("id").asString();
	}

	private static JsonNode find(JsonNode list, String field, String value) {
		var found = new ArrayList<JsonNode>();
		list.forEach(n -> {
			if (value.equals(n.get(field).asString())) {
				found.add(n);
			}
		});
		assertThat(found).as("%s = %s", field, value).isNotEmpty();
		return found.getFirst();
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
