package com.playchale.api.games.web;

import java.time.Instant;
import java.time.OffsetDateTime;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A host changing a game they made: what can change and when, who hears about it, and what can't
 * change once players have paid or the game has begun.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GameChangeApiTest {

	private static final String NEW_GAME = """
			{"sport":"football","format":"5-a-side","title":"Sunday 5s","startsAt":"2030-06-02T16:00:00Z","durationMinutes":60,
			 "venue":{"kind":"unlisted","name":"Legon Park","area":"Legon"},"capacity":10,"totalCost":%d,"pricing":"%s","visibility":"public"}
			""";

	/** The web app's GameChanges, sent whole. */
	private static final String CHANGE = """
			{"format":"5-a-side","title":"%s","notes":"Bring bibs","startsAt":"%s","durationMinutes":%d,"capacity":%d,
			 "totalCost":%d,"pricing":"%s","visibility":"public","venue":%s}
			""";

	private static final String LEGON = "{\"name\":\"Legon Park\",\"area\":\"Legon\"}";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper json;

	private Cookie host;

	private Cookie kojo;

	@BeforeEach
	void setUp() throws Exception {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, venues, pitches, bookings, games, game_participants, notifications, audit_events CASCADE")
			.update();
		host = TestSignIn.as(mvc, "024 455 5123");
		kojo = TestSignIn.as(mvc, "024 455 5124");
		send(patch("/me"), host, "{\"name\":\"Kwame Mensah\"}");
	}

	@Test
	void theHostMovesTheGameAndEveryoneInItHears() throws Exception {
		var id = game(0, "split");
		ok(send(post("/games/" + id + "/players"), kojo, ""), 200);

		// A new name and notes: nothing anyone needs a notification for.
		var renamed = ok(send(patch("/games/" + id), host, CHANGE.formatted("Sunday league warm-up", "2030-06-02T16:00:00Z", 60, 10, 0, "split", LEGON)), 200);
		assertThat(renamed.get("title").asString()).isEqualTo("Sunday league warm-up");
		assertThat(renamed.get("notes").asString()).isEqualTo("Bring bibs");
		assertThat(notifications("game-moved")).isZero();

		// Somewhere else, an hour later, pinned: Kojo hears where and when.
		var moved = ok(send(patch("/games/" + id), host, CHANGE.formatted("Sunday league warm-up", "2030-06-02T17:00:00Z", 90, 12, 0, "split", """
				{"name":"Labone Astro","area":"Labone","pin":{"lat":5.564,"lng":-0.1691,"source":"own"}}""")), 200);
		assertThat(moved.get("startsAt").asString()).isEqualTo("2030-06-02T17:00:00Z");
		assertThat(moved.get("durationMinutes").asInt()).isEqualTo(90);
		assertThat(moved.get("capacity").asInt()).isEqualTo(12);
		assertThat(moved.at("/venue/name").asString()).isEqualTo("Labone Astro");
		assertThat(moved.at("/venue/pin/lat").asDouble()).isEqualTo(5.564);
		assertThat(notifications("game-moved")).isEqualTo(1);
		var told = jdbc.sql("SELECT title || ' / ' || body FROM notifications WHERE kind = 'game-moved'").query(String.class).single();
		assertThat(told).startsWith("Sunday league warm-up has moved / Kwame moved it to Labone Astro, ");
		assertThat(jdbc.sql("SELECT count(*) FROM audit_events WHERE event_type = 'game.changed'").query(Integer.class).single()).isEqualTo(1);
	}

	@Test
	void onlyTheHostAndNeverBelowThePlayersAlreadyIn() throws Exception {
		var id = game(0, "split");
		ok(send(post("/games/" + id + "/players"), kojo, ""), 200);
		send(patch("/games/" + id), kojo, CHANGE.formatted("Mine now", "2030-06-02T16:00:00Z", 60, 10, 0, "split", LEGON))
			.andExpect(status().isConflict());
		send(patch("/games/" + id), host, CHANGE.formatted("Sunday 5s", "2030-06-02T16:00:00Z", 60, 1, 0, "split", LEGON))
			.andExpect(status().isUnprocessableContent());
		send(patch("/games/" + id), host, CHANGE.formatted("Sunday 5s", "2020-06-02T16:00:00Z", 60, 10, 0, "split", LEGON))
			.andExpect(status().isUnprocessableContent());

		// Started, or called off: past changing.
		jdbc.sql("UPDATE games SET starts_at = now() - interval '10 minutes' WHERE id = :id").param("id", UUID.fromString(id)).update();
		send(patch("/games/" + id), host, CHANGE.formatted("Sunday 5s", "2030-06-02T16:00:00Z", 60, 10, 0, "split", LEGON))
			.andExpect(status().isConflict());
	}

	@Test
	void aFreeGameThatStartsCostingAsksEveryoneButOncePaidThePriceStays() throws Exception {
		var id = game(0, "split");
		ok(send(post("/games/" + id + "/players"), kojo, ""), 200);
		var kojoId = jdbc.sql("SELECT id::text FROM users WHERE phone = '+233244555124'").query(String.class).single();

		// Free, then GH₵ 20 each: Kojo owes his share now, and hears so.
		var priced = ok(send(patch("/games/" + id), host, CHANGE.formatted("Sunday 5s", "2030-06-02T16:00:00Z", 60, 10, 20000, "per-player", LEGON)), 200);
		assertThat(priced.get("share").asLong()).isEqualTo(2000);
		assertThat(paid(id, kojoId)).isFalse();
		assertThat(jdbc.sql("SELECT body FROM notifications WHERE kind = 'game-moved'").query(String.class).single())
			.isEqualTo("Kwame changed what each player pays. It’s GH₵ 20 each now.");

		// He pays in cash. More spots at the same price per player is fine; another price isn't.
		ok(send(post("/games/" + id + "/players/" + kojoId + "/cash"), host, ""), 200);
		var bigger = ok(send(patch("/games/" + id), host, CHANGE.formatted("Sunday 5s", "2030-06-02T16:00:00Z", 60, 12, 24000, "per-player", LEGON)), 200);
		assertThat(bigger.get("share").asLong()).isEqualTo(2000);
		send(patch("/games/" + id), host, CHANGE.formatted("Sunday 5s", "2030-06-02T16:00:00Z", 60, 12, 36000, "per-player", LEGON))
			.andExpect(status().isConflict());
		assertThat(paid(id, kojoId)).isTrue();
	}

	@Test
	void aBookedPitchMovesWithTheGameWhenTheNewTimeIsFree() throws Exception {
		var owner = TestSignIn.as(mvc, "024 410 0200");
		var venue = ok(send(post("/venues"), owner, """
				{"name":"Halfway Line Turf","area":"Osu, Accra",
				 "pitches":[{"name":"Pitch A","sport":"football","format":"5-a-side","surface":"turf","pricePerHour":25000}],
				 "hours":[{"open":"06:00","close":"23:00"},{"open":"06:00","close":"23:00"},{"open":"06:00","close":"23:00"},{"open":"06:00","close":"23:00"},
				          {"open":"06:00","close":"23:00"},{"open":"06:00","close":"23:00"},{"open":"06:00","close":"23:00"}],
				 "amenities":[]}
				"""), 201);
		var venueId = venue.get("id").asString();
		var pitchId = venue.at("/pitches/0/id").asString();
		var booked = """
				{"sport":"football","format":"5-a-side","title":"Pitch A 5s","startsAt":"%s","durationMinutes":60,
				 "venue":{"kind":"listed","venueId":"%s","name":"Halfway Line Turf","area":"Osu, Accra","pitchId":"%s"},
				 "capacity":10,"totalCost":25000,"visibility":"public"}
				""";
		var id = ok(send(post("/games"), host, booked.formatted("2030-06-02T16:00:00Z", venueId, pitchId)), 201).get("id").asString();
		// Someone else has 18:00.
		ok(send(post("/games"), kojo, booked.formatted("2030-06-02T18:00:00Z", venueId, pitchId)), 201);

		// 17:00 for an hour is free: the booking moves, and the venue hears.
		var moved = ok(send(patch("/games/" + id), host, CHANGE.formatted("Pitch A 5s", "2030-06-02T17:00:00Z", 60, 10, 25000, "split", "null")), 200);
		assertThat(moved.get("startsAt").asString()).isEqualTo("2030-06-02T17:00:00Z");
		assertThat(bookedFrom(id)).isEqualTo(Instant.parse("2030-06-02T17:00:00Z"));
		assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE kind = 'booking' AND title = 'Kwame moved their booking'").query(Integer.class)
			.single()).isEqualTo(1);

		// Two hours from 17:00 runs into the 18:00 game: refused, and the booking stays put.
		send(patch("/games/" + id), host, CHANGE.formatted("Pitch A 5s", "2030-06-02T17:00:00Z", 120, 10, 25000, "split", "null"))
			.andExpect(status().isConflict());
		assertThat(bookedFrom(id)).isEqualTo(Instant.parse("2030-06-02T17:00:00Z"));

		// Earlier, and longer: the pitch's price for 90 minutes is the cost, whatever was sent.
		var longer = ok(send(patch("/games/" + id), host, CHANGE.formatted("Pitch A 5s", "2030-06-02T15:00:00Z", 90, 10, 1, "per-player", "null")), 200);
		assertThat(longer.get("totalCost").asLong()).isEqualTo(37500);
		assertThat(longer.get("pricing").asString()).isEqualTo("split");
		assertThat(longer.at("/venue/name").asString()).isEqualTo("Halfway Line Turf");
	}

	private String game(long cost, String pricing) throws Exception {
		return ok(send(post("/games"), host, NEW_GAME.formatted(cost, pricing)), 201).get("id").asString();
	}

	private int notifications(String kind) {
		return jdbc.sql("SELECT count(*) FROM notifications WHERE kind = :kind").param("kind", kind).query(Integer.class).single();
	}

	private boolean paid(String gameId, String userId) {
		return jdbc.sql("SELECT paid FROM game_participants WHERE game_id = :game AND user_id = :user")
			.param("game", UUID.fromString(gameId)).param("user", UUID.fromString(userId)).query(Boolean.class).single();
	}

	private Instant bookedFrom(String gameId) {
		return jdbc.sql("SELECT starts_at FROM bookings WHERE game_id = :game AND status = 'confirmed'").param("game", UUID.fromString(gameId))
			.query(OffsetDateTime.class).single().toInstant();
	}

	private ResultActions send(MockHttpServletRequestBuilder request, Cookie who, String body) throws Exception {
		return mvc.perform(request.cookie(who).contentType(MediaType.APPLICATION_JSON).content(body.isEmpty() ? "{}" : body));
	}

	private JsonNode ok(ResultActions result, int status) throws Exception {
		return json.readTree(result.andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
	}

}
