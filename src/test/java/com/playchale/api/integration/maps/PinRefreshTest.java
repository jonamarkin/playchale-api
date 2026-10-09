package com.playchale.api.integration.maps;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.TestSignIn;
import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.shared.maps.PinTable;
import com.playchale.api.shared.scheduling.ClusterLock;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The daily look-up that keeps place pins within Google's terms: a place's coordinates are looked up
 * again before they're 30 days old, or cleared; a spot someone set is theirs and left alone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, PinRefreshTest.Places.class })
class PinRefreshTest {

	/** Google, as far as these tests go: each place ID's answer, or a failure for any it doesn't know. */
	@TestConfiguration
	static class Places {

		@Bean
		FakePlaces fakePlaces() {
			return new FakePlaces();
		}

	}

	static class FakePlaces implements PlaceLocator {

		final Map<String, Optional<Located>> answers = new HashMap<>();

		@Override
		public Optional<Located> locate(String placeId) {
			var answer = answers.get(placeId);
			if (answer == null) {
				throw new IllegalStateException("Google is unreachable");
			}
			return answer;
		}

	}

	private static final String VENUE = """
			{"name":"%s","area":"Osu, Accra","pin":%s,
			 "pitches":[{"name":"Pitch A","sport":"football","format":"5-a-side","surface":"turf","pricePerHour":25000}],
			 "hours":[null,{"open":"06:00","close":"23:00"},{"open":"06:00","close":"23:00"},{"open":"06:00","close":"23:00"},
			          {"open":"06:00","close":"23:00"},{"open":"06:00","close":"23:00"},{"open":"06:00","close":"24:00"}],
			 "amenities":[]}
			""";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ObjectMapper json;

	@Autowired
	PinRefresh refresh;

	@Autowired
	FakePlaces google;

	@Autowired
	List<PinTable> tables;

	@Autowired
	ClusterLock lock;

	@Autowired
	TransactionTemplate transactions;

	private Cookie owner;

	@BeforeEach
	void setUp() throws Exception {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, venues, pitches, bookings, games, game_participants, notifications, audit_events CASCADE")
			.update();
		google.answers.clear();
		owner = TestSignIn.as(mvc, "024 410 0200");
	}

	@Test
	void aPlaceIsLookedUpAgainBeforeItsCoordinatesAreThirtyDaysOld() {
		var moved = venue("Moved", place("ChIJmovedPlaceOld0001"), 26);
		var fresh = venue("Fresh", place("ChIJfreshPlace000001"), 10);
		var gone = venue("Gone", place("ChIJgonePlace0000001"), 27);
		var own = venue("Own", "{\"lat\":5.5602,\"lng\":-0.1818,\"source\":\"own\"}", 400);
		google.answers.put("ChIJmovedPlaceOld0001", Optional.of(new PlaceLocator.Located("ChIJmovedPlaceNew0001", 5.5611, -0.1822)));
		google.answers.put("ChIJgonePlace0000001", Optional.empty());

		run(refresh);

		var now = Instant.now();
		assertThat(row(moved)).containsEntry("latitude", 5.5611).containsEntry("place_id", "ChIJmovedPlaceNew0001");
		assertThat((Instant) row(moved).get("pinned_at")).isBetween(now.minusSeconds(60), now);
		// Not due yet: Google isn't asked.
		assertThat(row(fresh)).containsEntry("latitude", 5.5602);
		// Google no longer has it: off the map, the link for directions stays.
		assertThat(row(gone)).containsEntry("pin_source", null).containsEntry("place_id", null).containsEntry("latitude", null);
		assertThat(jdbc.sql("SELECT map_url FROM venues WHERE id = :id").param("id", gone).query(String.class).single()).contains("query_place_id");
		// Set by hand: theirs, however old.
		assertThat(row(own)).containsEntry("latitude", 5.5602).containsEntry("pin_source", "own");
	}

	@Test
	void onesThatCantBeLookedUpLoseTheirCoordinatesInsideThirtyDaysButKeepThePlace() {
		var lastDay = venue("Last day", place("ChIJunreachable00001"), 29);
		var stillTime = venue("Still time", place("ChIJunreachable00002"), 26);

		run(refresh);

		assertThat(row(lastDay)).containsEntry("latitude", null).containsEntry("longitude", null)
			.containsEntry("place_id", "ChIJunreachable00001").containsEntry("pin_source", "place");
		assertThat(row(stillTime)).containsEntry("latitude", 5.5602);

		// Back on the map once Google answers again.
		google.answers.put("ChIJunreachable00001", Optional.of(new PlaceLocator.Located("ChIJunreachable00001", 5.5603, -0.1819)));
		run(refresh);
		assertThat(row(lastDay)).containsEntry("latitude", 5.5603);
	}

	@Test
	void withoutAServerKeyNothingIsLookedUpAndOldPlacesAreStillCleared() {
		var old = venue("Old", place("ChIJnoKeyPlace000001"), 29);
		var recent = venue("Recent", place("ChIJnoKeyPlace000002"), 26);
		var noKey = new PinRefresh(tables, new DefaultListableBeanFactory().getBeanProvider(PlaceLocator.class), lock, Clock.systemUTC());

		run(noKey);

		assertThat(row(old)).containsEntry("latitude", null).containsEntry("place_id", "ChIJnoKeyPlace000001");
		assertThat(row(recent)).containsEntry("latitude", 5.5602);
	}

	@Test
	void aPastGamesPlaceIsntLookedUpOnlyCleared() throws Exception {
		google.answers.put("ChIJpastGamePlace0001", Optional.of(new PlaceLocator.Located("ChIJpastGamePlace0001", 1.0, 1.0)));
		var game = json.readTree(mvc.perform(post("/games").cookie(owner).contentType(MediaType.APPLICATION_JSON).content("""
				{"sport":"football","format":"5-a-side","title":"Beach 5s","startsAt":"2030-06-02T16:00:00Z","durationMinutes":60,
				 "venue":{"kind":"unlisted","name":"Labadi Beach","pin":%s},"capacity":10,"totalCost":0,"visibility":"public"}
				""".formatted(place("ChIJpastGamePlace0001")))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		var id = UUID.fromString(game.get("id").asString());
		jdbc.sql("UPDATE games SET starts_at = now() - interval '40 days', pinned_at = now() - interval '29 days 1 hour' WHERE id = :id")
			.param("id", id).update();

		run(refresh);

		var pin = jdbc.sql("SELECT latitude, place_id FROM games WHERE id = :id").param("id", id).query().singleRow();
		assertThat(pin.get("latitude")).isNull();
		assertThat(pin.get("place_id")).isEqualTo("ChIJpastGamePlace0001");
	}

	private void run(PinRefresh job) {
		transactions.executeWithoutResult(t -> job.refresh());
	}

	private static String place(String id) {
		return "{\"lat\":5.5602,\"lng\":-0.1818,\"placeId\":\"%s\",\"source\":\"place\"}".formatted(id);
	}

	/** A venue with this pin, last looked up this many days ago. */
	private UUID venue(String name, String pin, int daysOld) {
		try {
			var body = mvc.perform(post("/venues").cookie(owner).contentType(MediaType.APPLICATION_JSON).content(VENUE.formatted(name, pin)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
			var id = UUID.fromString(json.readTree(body).get("id").asString());
			jdbc.sql("UPDATE venues SET pinned_at = :at WHERE id = :id")
				.param("at", Instant.now().minus(Duration.ofDays(daysOld)).atOffset(ZoneOffset.UTC)).param("id", id).update();
			return id;
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private Map<String, Object> row(UUID venue) {
		var row = new HashMap<String, Object>(
				jdbc.sql("SELECT latitude, longitude, place_id, pin_source, pinned_at FROM venues WHERE id = :id").param("id", venue).query().singleRow());
		if (row.get("pinned_at") instanceof OffsetDateTime at) {
			row.put("pinned_at", at.toInstant());
		}
		else if (row.get("pinned_at") instanceof java.sql.Timestamp at) {
			row.put("pinned_at", at.toInstant());
		}
		return row;
	}

}
