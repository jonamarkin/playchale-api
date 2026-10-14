package com.playchale.api.venues.web;

import java.util.Set;
import java.util.UUID;

import com.playchale.api.TestSignIn;
import com.playchale.api.TestVenues;
import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.venues.api.VenueReview;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Venues over HTTP, with the JSON the web app's venue screens send and expect. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class VenueApiTest {

	/** The web app's Venue type (webapp/app/types/domain.ts). If this changes, both must. */
	private static final Set<String> VENUE_FIELDS = Set.of("id", "name", "area", "sports", "listed", "ownerId", "description",
			"address", "phone", "pitches", "hours", "amenities", "createdAt", "country", "currency", "timezone", "review", "reviewNote");

	/** What the web app's venue form sends: Sundays closed. */
	private static final String OSU = """
			{"name":"Halfway Line Turf","area":"Osu, Accra","description":"Two floodlit turfs","phone":"024 410 0200",
			 "pitches":[{"name":"Pitch A","sport":"football","format":"5-a-side","surface":"turf","pricePerHour":25000}],
			 "hours":[null,{"open":"06:00","close":"23:00"},{"open":"06:00","close":"23:00"},{"open":"06:00","close":"23:00"},
			          {"open":"06:00","close":"23:00"},{"open":"06:00","close":"23:00"},{"open":"06:00","close":"24:00"}],
			 "amenities":["floodlights","water"]}
			""";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	VenueReview review;

	@Autowired
	ObjectMapper json;

	@BeforeEach
	void emptyTables() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, venues, pitches, bookings, games, notifications CASCADE").update();
	}

	@Test
	void anOwnerListsAVenueAndPlayersFindIt() throws Exception {
		mvc.perform(post("/venues").contentType(MediaType.APPLICATION_JSON).content(OSU)).andExpect(status().isUnauthorized());

		var adwoa = TestSignIn.as(mvc, "024 410 0200");
		var created = mvc.perform(post("/venues").cookie(adwoa).contentType(MediaType.APPLICATION_JSON).content(OSU))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.sports[0]").value("football"))
			.andExpect(jsonPath("$.hours[0]").isEmpty())
			.andExpect(jsonPath("$.hours[6].close").value("24:00"))
			.andExpect(jsonPath("$.pitches[0].pricePerHour").value(25000))
			.andReturn().getResponse().getContentAsString();
		var venue = json.readTree(created);
		assertThat(VENUE_FIELDS).containsAll(venue.propertyNames());
		var id = venue.get("id").asString();
		TestVenues.approveAll(jdbc);

		mvc.perform(get("/venues").param("query", "osu")).andExpect(jsonPath("$[0].id").value(id));
		mvc.perform(get("/venues").param("query", "kumasi")).andExpect(jsonPath("$").isEmpty());
		mvc.perform(get("/venues/" + id))
			.andExpect(jsonPath("$.owner.phone").value(""))
			.andExpect(jsonPath("$.phone").value("+233244100200"));
		mvc.perform(get("/me/venues").cookie(adwoa)).andExpect(jsonPath("$[0].id").value(id));
		mvc.perform(get("/venues/" + id + "/availability").param("date", "2030-01-01"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(17));

		var kojo = TestSignIn.as(mvc, "024 455 5124");
		mvc.perform(put("/venues/" + id).cookie(kojo).contentType(MediaType.APPLICATION_JSON).content(OSU))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.message").value("Only the venue’s owner can do that."));
	}

	@Test
	void aNewVenueWaitsForStaffBeforePlayersCanFindOrBookIt() throws Exception {
		var adwoa = TestSignIn.as(mvc, "024 410 0200");
		var kojo = TestSignIn.as(mvc, "024 455 5124");
		var id = json.readTree(mvc.perform(post("/venues").cookie(adwoa).contentType(MediaType.APPLICATION_JSON).content(OSU))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.review").value("pending"))
			.andReturn().getResponse().getContentAsString()).get("id").asString();
		var venueId = UUID.fromString(id);

		// Waiting: nowhere for players, but its owner sees it, and that it's waiting.
		mvc.perform(get("/venues").param("query", "osu")).andExpect(jsonPath("$").isEmpty());
		mvc.perform(get("/venues/" + id)).andExpect(status().isNotFound());
		mvc.perform(get("/venues/" + id).cookie(kojo)).andExpect(status().isNotFound());
		mvc.perform(get("/venues/" + id).cookie(adwoa)).andExpect(jsonPath("$.review").value("pending"));
		mvc.perform(get("/me/venues").cookie(adwoa)).andExpect(jsonPath("$[0].review").value("pending"));

		// Declined, with what to change: still hidden, and only the owner reads the note.
		var owner = UUID.fromString(json.readTree(mvc.perform(get("/auth/session").cookie(adwoa)).andReturn().getResponse().getContentAsString())
			.get("id").asString());
		review.decline(venueId, "Say where the entrance is: we couldn’t find it on the map.", owner);
		mvc.perform(get("/me/venues").cookie(adwoa))
			.andExpect(jsonPath("$[0].review").value("declined"))
			.andExpect(jsonPath("$[0].reviewNote").value("Say where the entrance is: we couldn’t find it on the map."));
		assertThat(jdbc.sql("SELECT title FROM notifications WHERE user_id = :id").param("id", owner).query(String.class).list())
			.containsExactly("Halfway Line Turf needs a change");

		// The owner changes it: back to staff.
		mvc.perform(put("/venues/" + id).cookie(adwoa).contentType(MediaType.APPLICATION_JSON).content(OSU))
			.andExpect(jsonPath("$.review").value("pending"));

		// Approved: players find it, and its note is gone.
		review.approve(venueId, owner);
		mvc.perform(get("/venues").param("query", "osu")).andExpect(jsonPath("$[0].id").value(id));
		mvc.perform(get("/venues/" + id).cookie(kojo)).andExpect(jsonPath("$.review").value("approved")).andExpect(jsonPath("$.reviewNote").doesNotExist());
		assertThat(jdbc.sql("SELECT title FROM notifications WHERE user_id = :id ORDER BY created_at DESC LIMIT 1").param("id", owner)
			.query(String.class).single()).isEqualTo("Halfway Line Turf is live");
	}

	@Test
	void aVenueNotLiveCantBeBookedForAGame() throws Exception {
		var adwoa = TestSignIn.as(mvc, "024 410 0200");
		var kojo = TestSignIn.as(mvc, "024 455 5124");
		var venue = json.readTree(mvc.perform(post("/venues").cookie(adwoa).contentType(MediaType.APPLICATION_JSON).content(OSU))
			.andReturn().getResponse().getContentAsString());
		var game = """
				{"sport":"football","format":"5-a-side","title":"Osu 5s","startsAt":"2030-01-01T16:00:00Z","durationMinutes":60,
				 "venue":{"kind":"listed","venueId":"%s","name":"Halfway Line Turf","area":"Osu, Accra","pitchId":"%s"},
				 "capacity":10,"totalCost":25000,"visibility":"public"}
				""".formatted(venue.get("id").asString(), venue.at("/pitches/0/id").asString());
		mvc.perform(post("/games").cookie(kojo).contentType(MediaType.APPLICATION_JSON).content(game))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.message").value("Halfway Line Turf isn’t taking bookings on PlayChale yet."));
		TestVenues.approveAll(jdbc);
		mvc.perform(post("/games").cookie(kojo).contentType(MediaType.APPLICATION_JSON).content(game)).andExpect(status().isCreated());
	}

	@Test
	void anOwnerBlocksTimeAndSeesItOnTheirSchedule() throws Exception {
		var adwoa = TestSignIn.as(mvc, "024 410 0200");
		var venue = json.readTree(mvc.perform(post("/venues").cookie(adwoa).contentType(MediaType.APPLICATION_JSON).content(OSU))
			.andReturn().getResponse().getContentAsString());
		var id = venue.get("id").asString();
		var pitch = venue.get("pitches").get(0).get("id").asString();

		var block = mvc.perform(post("/venues/" + id + "/blocks").cookie(adwoa).contentType(MediaType.APPLICATION_JSON).content("""
				{"pitchId":"%s","startsAt":"2030-01-01T10:00:00Z","endsAt":"2030-01-01T12:00:00Z","note":"Regulars, cash"}
				""".formatted(pitch)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.kind").value("block"))
			.andExpect(jsonPath("$.price").value(0))
			.andReturn().getResponse().getContentAsString();

		mvc.perform(get("/venues/" + id + "/schedule").cookie(adwoa).param("from", "2030-01-01T00:00:00Z").param("to", "2030-01-02T00:00:00Z"))
			.andExpect(jsonPath("$[0].pitchName").value("Pitch A"))
			.andExpect(jsonPath("$[0].note").value("Regulars, cash"));

		mvc.perform(delete("/bookings/" + json.readTree(block).get("id").asString()).cookie(adwoa)).andExpect(status().isNoContent());
		mvc.perform(get("/venues/" + id + "/schedule").cookie(adwoa).param("from", "2030-01-01T00:00:00Z").param("to", "2030-01-02T00:00:00Z"))
			.andExpect(jsonPath("$").isEmpty());
	}

}
