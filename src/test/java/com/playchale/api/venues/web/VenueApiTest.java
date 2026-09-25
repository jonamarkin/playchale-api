package com.playchale.api.venues.web;

import java.util.Set;

import com.playchale.api.TestSignIn;
import com.playchale.api.TestcontainersConfiguration;
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
			"address", "phone", "pitches", "hours", "amenities", "createdAt");

	/** What the web app's venue form sends: Sundays closed. */
	private static final String OSU = """
			{"name":"Osu Astro Turf","area":"Osu, Accra","description":"Two floodlit turfs","phone":"024 410 0200",
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
	ObjectMapper json;

	@BeforeEach
	void emptyTables() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, venues, pitches, bookings CASCADE").update();
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
