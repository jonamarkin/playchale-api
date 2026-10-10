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

/**
 * A corporate games day: thirty-odd companies, each paying a fee, each with a rep who registers its
 * own staff and enters them in games, and sponsors on the public page and the board.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EventCorporateGamesApiTest {

	/** A real PNG's first bytes, for a logo. */
	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13 };

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper json;

	private Cookie admin;

	private Cookie rep;

	private Cookie player;

	private String workspace;

	private String eventId;

	private JsonNode event;

	@BeforeEach
	void setUp() throws Exception {
		jdbc.sql("""
				TRUNCATE users, sign_in_codes, sessions, organisations, organisation_memberships, organisation_invitations,
				         events, notifications, audit_events CASCADE
				""").update();
		admin = TestSignIn.as(mvc, "024 455 5911");
		rep = TestSignIn.as(mvc, "024 455 5912");
		player = TestSignIn.as(mvc, "024 455 5913");
		ok(send(patch("/me"), rep, "{\"name\":\"Esi Mensah\"}"), 200);
		workspace = ok(send(post("/organisations"), admin, """
				{"name":"Meridian Media","slug":"meridian-media","country":"GH","primaryColour":"#0c3a3a"}
				"""), 201).at("/organisation/id").asString();
		event = ok(send(post("/organisations/" + workspace + "/events"), admin, """
				{"name":"Accra Business Games","startsOn":"2030-08-29","groupFee":500000,
				 "groups":[{"name":"Northgate Bank"},{"name":"Sunline Telecom"}]}
				"""), 201);
		eventId = event.at("/event/id").asString();
	}

	@Test
	void thirtyCompaniesFitAndEachPaysItsFee() throws Exception {
		var groups = new ArrayList<String>();
		for (var n = 1; n <= 62; n++) {
			groups.add("{\"name\":\"Company %d\"}".formatted(n));
		}
		var big = ok(send(post("/organisations/" + workspace + "/events"), admin,
				"{\"name\":\"Big day\",\"startsOn\":\"2030-09-01\",\"groups\":[%s]}".formatted(String.join(",", groups))), 201);
		var bigId = big.at("/event/id").asString();
		ok(send(post("/events/" + bigId + "/groups"), admin, "{\"name\":\"Company 63\"}"), 200);
		ok(send(post("/events/" + bigId + "/groups"), admin, "{\"name\":\"Company 64\"}"), 200);
		assertThat(ok(send(post("/events/" + bigId + "/groups"), admin, "{\"name\":\"Company 65\"}"), 422).at("/error/message").asString())
			.isEqualTo("An event can have up to 64 groups.");

		// The fee per company: tracked, paid to the organisers by bank transfer here.
		assertThat(event.at("/event/groupFee").asLong()).isEqualTo(500000);
		var northgate = event.at("/groups/0/id").asString();
		var paid = ok(send(put("/events/" + eventId + "/groups/" + northgate + "/fee"), admin, "{\"via\":\"bank\"}"), 200);
		assertThat(paid.at("/groups/0/feePaidVia").asString()).isEqualTo("bank");
		assertThat(paid.at("/groups/0").has("feePaidAt")).isTrue();
		assertThat(paid.at("/groups/1").has("feePaidVia")).isFalse();
		assertThat(ok(send(put("/events/" + eventId + "/groups/" + northgate + "/fee"), admin, "{\"via\":\"cheque\"}"), 422)
			.at("/error/message").asString()).isEqualTo("Say how they paid: cash, MoMo or a bank transfer.");
		ok(send(put("/events/" + eventId + "/groups/" + northgate + "/fee"), rep, "{\"via\":\"cash\"}"), 404);

		// Nobody else sees who paid; the public page shows no money at all.
		var code = event.at("/links/joinUrl").asString().split("code=")[1];
		var asPlayer = ok(send(post("/events/join/" + code), player, "{\"gameIds\":[],\"groupId\":\"%s\"}".formatted(northgate)), 200);
		assertThat(asPlayer.at("/groups/0").has("feePaidVia")).isFalse();
		assertThat(asPlayer.at("/groups/0").has("reps")).isFalse();
		var path = ok(send(put("/events/" + eventId + "/public-page"), admin, "{\"on\":true}"), 200).at("/event/publicPath").asString();
		var page = ok(mvc.perform(get("/public/events/" + path.substring(3))), 200);
		assertThat(page.at("/event").has("groupFee")).isFalse();
		assertThat(page.at("/groups/0").has("feePaidVia")).isFalse();

		// Off: 0 takes it away.
		ok(send(patch("/events/" + eventId), admin, "{\"name\":\"Accra Business Games\",\"startsOn\":\"2030-08-29\",\"groupFee\":0}"), 200);
		assertThat(ok(send(get("/events/" + eventId), admin, null), 200).at("/event").has("groupFee")).isFalse();
	}

	@Test
	void aRepRegistersTheirOwnCompanyAndNobodyElse() throws Exception {
		var northgate = event.at("/groups/0/id").asString();
		var sunline = event.at("/groups/1/id").asString();
		var repLink = event.at("/links/reps/0/url").asString();
		assertThat(event.at("/links/reps/0/groupId").asString()).isEqualTo(northgate);
		var code = repLink.split("code=")[1];

		// The link says what it's for before anyone signs in.
		var preview = ok(mvc.perform(get("/events/reps/" + code)), 200);
		assertThat(preview.at("/eventName").asString()).isEqualTo("Accra Business Games");
		assertThat(preview.at("/group/name").asString()).isEqualTo("Northgate Bank");
		assertThat(preview.has("mine")).isFalse();

		var joined = ok(send(post("/events/reps/" + code), rep, null), 200);
		assertThat(joined.at("/viewer/role").asString()).isEqualTo("rep");
		assertThat(joined.at("/viewer/represents/0").asString()).isEqualTo(northgate);
		assertThat(joined.at("/groups/0/reps/0/name").asString()).isEqualTo("Esi Mensah");
		assertThat(joined.at("/groups/0/feePaidVia").isMissingNode()).isTrue();
		assertThat(joined.at("/groups/1").has("reps")).isFalse();
		assertThat(ok(send(get("/events/reps/" + code), rep, null), 200).at("/mine").asBoolean()).isTrue();
		assertThat(ok(send(get("/events/" + eventId), admin, null), 200).at("/groups/0/reps/0/name").asString()).isEqualTo("Esi Mensah");

		// Their own company's staff, typed or pasted.
		var added = ok(send(post("/events/" + eventId + "/people"), rep, """
				{"people":[{"name":"Kwame Asante","groupId":"%s"},{"name":"Ama Owusu","groupId":"%s"}]}
				""".formatted(northgate, northgate)), 200);
		assertThat(added.get("people").size()).isEqualTo(2);
		assertThat(added.at("/people/0/source").asString()).isEqualTo("rep");
		var kwame = added.at("/people/1/id").asString();
		var ama = added.at("/people/0/id").asString();
		ok(send(post("/events/" + eventId + "/people"), rep, "{\"people\":[{\"name\":\"Yaw\",\"groupId\":\"%s\"}]}".formatted(sunline)), 409);
		ok(send(post("/events/" + eventId + "/people"), rep, "{\"people\":[{\"name\":\"Yaw\"}]}"), 409);
		var theirs = ok(send(post("/events/" + eventId + "/people"), admin, "{\"people\":[{\"name\":\"Yaw Boateng\",\"groupId\":\"%s\"}]}"
			.formatted(sunline)), 200).get("people");
		var yaw = theirs.valueStream().filter(p -> p.get("name").asString().equals("Yaw Boateng")).findFirst().orElseThrow().get("id").asString();
		ok(send(patch("/events/" + eventId + "/people/" + kwame), rep, "{\"name\":\"Kwame A. Asante\",\"groupId\":\"%s\"}".formatted(northgate)), 200);
		ok(send(patch("/events/" + eventId + "/people/" + kwame), rep, "{\"name\":\"Kwame\",\"groupId\":\"%s\"}".formatted(sunline)), 409);
		ok(send(delete("/events/" + eventId + "/people/" + yaw), rep, null), 409);

		// Into games: a single game, and their company's team.
		var draughts = ok(send(post("/events/" + eventId + "/games"), admin, "{\"discipline\":\"draughts\"}"), 200).at("/games/0/id").asString();
		var games = ok(send(post("/events/" + eventId + "/games"), admin, "{\"discipline\":\"tug-of-war\"}"), 200).get("games");
		var tug = games.valueStream().filter(g -> g.get("discipline").asString().equals("tug-of-war")).findFirst().orElseThrow().get("id").asString();
		ok(send(post("/events/" + eventId + "/games/" + draughts + "/entries"), rep, "{\"personIds\":[\"%s\"]}".formatted(kwame)), 200);
		ok(send(post("/events/" + eventId + "/games/" + draughts + "/entries"), rep, "{\"personIds\":[\"%s\"]}".formatted(yaw)), 409);
		var team = ok(send(post("/events/" + eventId + "/games/" + tug + "/entries"), rep, """
				{"groupId":"%s","personIds":["%s","%s"]}
				""".formatted(northgate, kwame, ama)), 200);
		var entry = team.get("games").valueStream().filter(g -> g.get("id").asString().equals(tug)).findFirst().orElseThrow().at("/entries/0");
		assertThat(entry.get("name").asString()).isEqualTo("Northgate Bank");

		// Once the draw is made, or sign-ups close, it's the organisers' to change.
		ok(send(post("/events/" + eventId + "/games/" + draughts + "/entries"), admin, "{\"personIds\":[\"%s\"]}".formatted(yaw)), 200);
		ok(send(post("/events/" + eventId + "/games/" + draughts + "/draw"), admin, null), 200);
		ok(send(post("/events/" + eventId + "/games/" + draughts + "/entries"), rep, "{\"personIds\":[\"%s\"]}".formatted(ama)), 409);
		ok(send(patch("/events/" + eventId), admin, "{\"name\":\"Accra Business Games\",\"startsOn\":\"2030-08-29\",\"registrationOpen\":false}"), 200);
		assertThat(ok(send(post("/events/" + eventId + "/people"), rep, "{\"people\":[{\"name\":\"Abena\",\"groupId\":\"%s\"}]}"
			.formatted(northgate)), 409).at("/error/message").asString()).isEqualTo("Sign-ups are closed. Ask the organisers to make changes.");

		// A new link stops the old one; taking the rep off ends their part.
		var renewed = ok(send(post("/events/" + eventId + "/groups/" + northgate + "/rep-link"), admin, null), 200);
		assertThat(renewed.at("/links/reps/0/url").asString()).isNotEqualTo(repLink);
		ok(mvc.perform(get("/events/reps/" + code)), 404);
		var repId = renewed.at("/groups/0/reps/0/userId").asString();
		ok(send(delete("/events/" + eventId + "/groups/" + northgate + "/reps/" + repId), admin, null), 200);
		ok(send(get("/events/" + eventId), rep, null), 404);
	}

	@Test
	void sponsorsShowOnThePublicPageAndTheBoard() throws Exception {
		var first = ok(send(post("/events/" + eventId + "/sponsors"), admin, "{\"name\":\"Kente Lager\",\"headline\":true}"), 200);
		var kente = first.at("/sponsors/0/id").asString();
		var both = ok(send(post("/events/" + eventId + "/sponsors"), admin, "{\"name\":\"Coastal Water\"}"), 200);
		assertThat(both.at("/sponsors/0/name").asString()).isEqualTo("Kente Lager");
		assertThat(both.at("/sponsors/0/headline").asBoolean()).isTrue();
		assertThat(both.at("/sponsors/1/headline").asBoolean()).isFalse();
		ok(send(post("/events/" + eventId + "/sponsors"), rep, "{\"name\":\"Mine\"}"), 404);

		// The headline goes to whoever's given it.
		var coastal = both.at("/sponsors/1/id").asString();
		var moved = ok(send(patch("/events/" + eventId + "/sponsors/" + coastal), admin, "{\"name\":\"Coastal Water\",\"headline\":true}"), 200);
		assertThat(moved.at("/sponsors/0/name").asString()).isEqualTo("Coastal Water");
		assertThat(moved.at("/sponsors/1/headline").asBoolean()).isFalse();

		// A logo, served to anyone.
		var withLogo = ok(mvc.perform(put("/events/" + eventId + "/sponsors/" + kente + "/logo").cookie(admin).contentType("image/png").content(PNG)),
				200);
		var logoUrl = withLogo.get("sponsors").valueStream().filter(s -> s.get("id").asString().equals(kente)).findFirst().orElseThrow()
			.get("logoUrl").asString();
		assertThat(logoUrl).startsWith("/events/sponsors/" + kente + "/logo?v=1");
		var served = mvc.perform(get(logoUrl)).andReturn().getResponse();
		assertThat(served.getStatus()).isEqualTo(200);
		assertThat(served.getContentType()).isEqualTo("image/png");
		ok(mvc.perform(put("/events/" + eventId + "/sponsors/" + kente + "/logo").cookie(admin).contentType("image/png").content(new byte[] { 1, 2, 3, 4, 5 })),
				422);

		var path = ok(send(put("/events/" + eventId + "/public-page"), admin, "{\"on\":true}"), 200).at("/event/publicPath").asString();
		assertThat(ok(mvc.perform(get("/public/events/" + path.substring(3))), 200).at("/sponsors").size()).isEqualTo(2);
		var board = ok(send(get("/events/" + eventId), admin, null), 200).at("/links/boardUrl").asString().replace("/events/board/", "");
		assertThat(ok(mvc.perform(get("/boards/" + board)), 200).at("/sponsors/0/name").asString()).isEqualTo("Coastal Water");

		// Next year's comes with the same sponsors and fee, new rep links, and no reps.
		var copy = ok(send(post("/events/" + eventId + "/copy"), admin, "{\"name\":\"Accra Business Games 2031\",\"startsOn\":\"2031-08-28\"}"), 201);
		assertThat(copy.get("sponsors").size()).isEqualTo(2);
		assertThat(copy.at("/event/groupFee").asLong()).isEqualTo(500000);
		assertThat(copy.at("/links/reps/0/url").asString()).isNotEqualTo(event.at("/links/reps/0/url").asString());
		assertThat(copy.at("/groups/0/reps").size()).isZero();

		ok(send(delete("/events/" + eventId + "/sponsors/" + kente), admin, null), 200);
		ok(mvc.perform(get(logoUrl)), 404);
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
		var body = response.getContentAsString();
		return body.isEmpty() ? json.nullNode() : json.readTree(body);
	}

}
