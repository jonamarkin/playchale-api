package com.playchale.api.shared.maps;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins on the map over HTTP, as the web app sends and shows them: on venues (and the games there),
 * on games anywhere else, on events, and Discover sorted nearest first.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MapPinsApiTest {

	private static final String LABONE_PLACE = "ChIJ8a8Bqb2a3w8R5oQkY6X3n2k";

	private static final String VENUE = """
			{"name":"Halfway Line Turf","area":"Osu, Accra","pin":%s,
			 "pitches":[{"name":"Pitch A","sport":"football","format":"5-a-side","surface":"turf","pricePerHour":25000}],
			 "hours":[null,{"open":"06:00","close":"23:00"},{"open":"06:00","close":"23:00"},{"open":"06:00","close":"23:00"},
			          {"open":"06:00","close":"23:00"},{"open":"06:00","close":"23:00"},{"open":"06:00","close":"24:00"}],
			 "amenities":[]}
			""";

	private static final String GAME = """
			{"sport":"football","format":"5-a-side","title":"%s","startsAt":"2030-06-02T16:00:00Z","durationMinutes":60,
			 "venue":%s,"capacity":10,"totalCost":0,"visibility":"public"}
			""";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper json;

	private Cookie adwoa;

	@BeforeEach
	void setUp() throws Exception {
		jdbc.sql("""
				TRUNCATE users, sign_in_codes, sessions, venues, pitches, bookings, games, game_participants, notifications, audit_events,
				         organisations, organisation_memberships, events CASCADE
				""").update();
		adwoa = TestSignIn.as(mvc, "024 410 0200");
	}

	@Test
	void aVenuePickedFromTheSearchKeepsItsPlaceAndItsGamesShowIt() throws Exception {
		var venue = ok(send(post("/venues"), VENUE.formatted("""
				{"lat":5.5602,"lng":-0.1818,"placeId":"%s","source":"place"}""".formatted(LABONE_PLACE))), 201);
		assertThat(venue.at("/pin/lat").asDouble()).isEqualTo(5.5602);
		assertThat(venue.at("/pin/placeId").asString()).isEqualTo(LABONE_PLACE);
		assertThat(venue.at("/pin/source").asString()).isEqualTo("place");
		// Directions by the place's ID, named after the venue in case Maps can't find it.
		assertThat(venue.get("mapUrl").asString()).isEqualTo(
				"https://www.google.com/maps/search/?api=1&query=Halfway%20Line%20Turf%2C%20Osu%2C%20Accra&query_place_id=" + LABONE_PLACE);

		var game = ok(send(post("/games"), GAME.formatted("Saturday 5s", """
				{"kind":"listed","venueId":"%s","name":"Halfway Line Turf","area":"Osu, Accra",
				 "pin":{"lat":1.0,"lng":1.0,"source":"own"}}""".formatted(venue.get("id").asString()))), 201);
		// A partner venue's game shows the venue's own pin, whatever was sent with it.
		assertThat(game.at("/venue/pin/lat").asDouble()).isEqualTo(5.5602);
		assertThat(game.at("/venue/mapUrl").asString()).contains("query_place_id=" + LABONE_PLACE);
	}

	@Test
	void savingTheVenueAgainKeepsAPlacesAgeSoItsCoordinatesAreNeverKeptTooLong() throws Exception {
		var pin = """
				{"lat":5.5602,"lng":-0.1818,"placeId":"%s","source":"place"}""".formatted(LABONE_PLACE);
		var venue = ok(send(post("/venues"), VENUE.formatted(pin)), 201);
		var id = UUID.fromString(venue.get("id").asString());
		var aged = Instant.parse("2026-01-01T00:00:00Z");
		jdbc.sql("UPDATE venues SET pinned_at = :at WHERE id = :id").param("at", aged.atOffset(ZoneOffset.UTC)).param("id", id).update();

		ok(send(put("/venues/" + id), VENUE.formatted(pin)), 200);
		assertThat(pinnedAt("venues", id)).isEqualTo(aged);

		// Moved under the pin onto the pitch: theirs now, dated now, directions to the spot.
		var moved = ok(send(put("/venues/" + id), VENUE.formatted("""
				{"lat":5.56031,"lng":-0.18172,"placeId":"%s","source":"own"}""".formatted(LABONE_PLACE))), 200);
		assertThat(pinnedAt("venues", id)).isAfter(aged);
		assertThat(moved.get("mapUrl").asString()).isEqualTo("https://www.google.com/maps/search/?api=1&query=5.56031,-0.18172");

		// Taken off the map, a pasted link is the way there again.
		var linked = ok(send(put("/venues/" + id), VENUE.formatted("null").replace("\"pin\":null", "\"mapUrl\":\"https://maps.app.goo.gl/abc123\"")), 200);
		assertThat(linked.has("pin")).isFalse();
		assertThat(linked.get("mapUrl").asString()).isEqualTo("https://maps.app.goo.gl/abc123");
	}

	@Test
	void aSpotOffTheMapIsRefused() throws Exception {
		send(post("/venues"), VENUE.formatted("{\"lat\":95,\"lng\":0}")).andExpect(status().isUnprocessableContent());
		send(post("/games"), GAME.formatted("Beach volley", """
				{"kind":"unlisted","name":"Labadi Beach","pin":{"lat":5.56,"lng":-0.15,"source":"place"}}"""))
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	void discoverSortedNearestFirstSaysHowFarEachIs() throws Exception {
		game("Tema run", "Community 5 park", "{\"lat\":5.6698,\"lng\":-0.0166}");
		game("Osu 5s", "Osu Castle park", "{\"lat\":5.5602,\"lng\":-0.1818,\"source\":\"own\"}");
		game("Nowhere yet", "Somewhere in Accra", null);
		game("Legon 5s", "Legon park", "{\"lat\":5.6358,\"lng\":-0.1601}");

		var near = ok(mvc.perform(get("/games").param("near", "5.5610,-0.1820").param("country", "GH")), 200);
		var titles = new ArrayList<String>();
		near.forEach(g -> titles.add(g.get("title").asString()));
		assertThat(titles).containsExactly("Osu 5s", "Legon 5s", "Tema run", "Nowhere yet");
		assertThat(near.get(0).get("distanceKm").asDouble()).isEqualTo(0.1);
		assertThat(near.get(1).get("distanceKm").asDouble()).isBetween(8.0, 9.5);
		assertThat(near.get(2).get("distanceKm").asDouble()).isBetween(21.0, 23.0);
		assertThat(near.get(3).has("distanceKm")).isFalse();
		assertThat(near.get(0).at("/venue/pin/source").asString()).isEqualTo("own");

		// Soonest first, as ever, without it; and a "near" that isn't a place on the map is ignored.
		var soonest = ok(mvc.perform(get("/games").param("near", "here").param("country", "GH")), 200);
		assertThat(soonest.size()).isEqualTo(4);
		assertThat(soonest.get(0).has("distanceKm")).isFalse();
	}

	@Test
	void anEventIsOnTheMapToo() throws Exception {
		var workspace = ok(send(post("/organisations"), """
				{"name":"Hillview Chapel","slug":"hillview-chapel","country":"GH","primaryColour":"#0c3a3a"}
				"""), 201);
		var event = ok(send(post("/organisations/" + workspace.at("/organisation/id").asString() + "/events"), """
				{"name":"Hillview Games Day","startsOn":"2030-06-01",
				 "venue":{"name":"Hillview Chapel grounds","area":"Adenta","pin":{"lat":5.7068,"lng":-0.1665,"source":"own"}}}
				"""), 201);
		assertThat(event.at("/event/venue/pin/lat").asDouble()).isEqualTo(5.7068);
		assertThat(event.at("/event/venue/mapUrl").asString()).isEqualTo("https://www.google.com/maps/search/?api=1&query=5.7068,-0.1665");
		var id = event.at("/event/id").asString();

		// Renamed, the same pin keeps its age.
		var aged = Instant.parse("2026-01-01T00:00:00Z");
		jdbc.sql("UPDATE events SET pinned_at = :at WHERE id = :id").param("at", aged.atOffset(ZoneOffset.UTC)).param("id", UUID.fromString(id))
			.update();
		var renamed = ok(send(patch("/events/" + id), """
				{"name":"Hillview Games Day 2030","startsOn":"2030-06-01",
				 "venue":{"name":"Hillview Chapel grounds","area":"Adenta","pin":{"lat":5.7068,"lng":-0.1665,"source":"own"}}}
				"""), 200);
		assertThat(renamed.at("/event/venue/pin/lng").asDouble()).isEqualTo(-0.1665);
		assertThat(pinnedAt("events", UUID.fromString(id))).isEqualTo(aged);

		// A pin with no name for the place won't do: people need to know what they're looking for.
		send(patch("/events/" + id), """
				{"name":"Hillview Games Day 2030","startsOn":"2030-06-01","venue":{"pin":{"lat":5.7068,"lng":-0.1665}}}
				""").andExpect(status().isUnprocessableContent());
	}

	private void game(String title, String place, String pin) throws Exception {
		var venue = pin == null ? "{\"kind\":\"unlisted\",\"name\":\"%s\"}".formatted(place)
				: "{\"kind\":\"unlisted\",\"name\":\"%s\",\"pin\":%s}".formatted(place, pin);
		ok(send(post("/games"), GAME.formatted(title, venue)), 201);
	}

	private Instant pinnedAt(String table, UUID id) {
		return jdbc.sql("SELECT pinned_at FROM " + table + " WHERE id = :id").param("id", id).query(OffsetDateTime.class).single().toInstant();
	}

	private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
		return mvc.perform(request.cookie(adwoa).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private JsonNode ok(ResultActions result, int status) throws Exception {
		return json.readTree(result.andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
	}

}
