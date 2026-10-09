package com.playchale.api.shared.maps;

import java.time.Instant;

import com.playchale.api.shared.error.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What a pin keeps, how it gives directions, and how far apart two places are. */
class PinTest {

	private static final Instant MONDAY = Instant.parse("2030-06-03T10:00:00Z");

	private static final Instant LATER = Instant.parse("2030-06-20T10:00:00Z");

	private static final String LABONE = "ChIJ8a8Bqb2a3w8R5oQkY6X3n2k";

	@Test
	void aSpotSomeoneSetIsTheirsAndGivesCoordinatesForDirections() {
		var pin = Pin.from(new MapPin(5.5640012345, -0.16911, null, null), null, MONDAY);
		assertThat(pin.source()).isEqualTo(Pin.OWN);
		assertThat(pin.latitude()).isEqualTo(5.564001);
		assertThat(pin.pinnedAt()).isEqualTo(MONDAY);
		assertThat(pin.directions("Labone Astro")).isEqualTo("https://www.google.com/maps/search/?api=1&query=5.564001,-0.16911");
		assertThat(pin.view()).isEqualTo(new MapPin(5.564001, -0.16911, null, "own"));
	}

	@Test
	void aPlaceFromTheSearchGivesDirectionsByItsIdWhichCanBeKept() {
		var pin = Pin.from(new MapPin(5.564, -0.1691, LABONE, "place"), null, MONDAY);
		assertThat(pin.directions("Labone Astro, Labone")).isEqualTo(
				"https://www.google.com/maps/search/?api=1&query=Labone%20Astro%2C%20Labone&query_place_id=" + LABONE);
	}

	@Test
	void theSamePinSentBackKeepsItsAgeSoAPlaceIsNeverKeptLongerBySavingAgain() {
		var first = Pin.from(new MapPin(5.564, -0.1691, LABONE, "place"), null, MONDAY);
		assertThat(Pin.from(new MapPin(5.564, -0.1691, LABONE, "place"), first, LATER).pinnedAt()).isEqualTo(MONDAY);
		// Moved under the pin: now it's a spot of their own, dated when they moved it.
		var moved = Pin.from(new MapPin(5.5642, -0.1693, LABONE, "own"), first, LATER);
		assertThat(moved.pinnedAt()).isEqualTo(LATER);
		assertThat(moved.source()).isEqualTo(Pin.OWN);
		assertThat(Pin.from(null, first, LATER)).isNull();
	}

	@Test
	void aPlaceFoundOnPlayChaleKeepsTheAgeOfItsCoordinates() {
		// Looked up with Google on Monday for one game, used for another later: still Monday's.
		var reused = Pin.from(new MapPin(5.564, -0.1691, LABONE, "place", MONDAY), null, LATER);
		assertThat(reused.pinnedAt()).isEqualTo(MONDAY);
		assertThat(reused.view().pinnedAt()).isEqualTo(MONDAY);
		// An age from the future, or on a spot of someone's own, counts for nothing.
		assertThat(Pin.from(new MapPin(5.564, -0.1691, LABONE, "place", LATER.plusSeconds(60)), null, LATER).pinnedAt()).isEqualTo(LATER);
		var own = Pin.from(new MapPin(5.564, -0.1691, null, "own", MONDAY), null, LATER);
		assertThat(own.pinnedAt()).isEqualTo(LATER);
		assertThat(own.view().pinnedAt()).isNull();
	}

	@Test
	void anythingOffTheMapIsRefused() {
		for (var bad : new MapPin[] { new MapPin(91.0, 0.0, null, null), new MapPin(0.0, 181.0, null, null), new MapPin(null, 0.0, null, null),
				new MapPin(Double.NaN, 0.0, null, null), new MapPin(5.5, -0.1, "not a place id!", "place"), new MapPin(5.5, -0.1, null, "place"),
				new MapPin(5.5, -0.1, null, "google") }) {
			assertThatThrownBy(() -> Pin.from(bad, null, MONDAY)).isInstanceOf(BusinessException.class)
				.hasMessage("That spot isn’t on the map. Search for the place again, or use your location.");
		}
	}

	@Test
	void distancesAreAlongTheGround() {
		// Osu to East Legon, about 9 km.
		assertThat(Pin.kmBetween(5.5602, -0.1818, 5.6358, -0.1601)).isBetween(8.5, 9.0);
		assertThat(Pin.kmBetween(5.5602, -0.1818, 5.5602, -0.1818)).isZero();
	}

}
