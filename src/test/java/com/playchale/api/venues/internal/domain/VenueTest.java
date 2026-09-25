package com.playchale.api.venues.internal.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.playchale.api.market.Market;
import com.playchale.api.shared.error.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rules for a venue. A plain unit test: no Spring, no database. */
class VenueTest {

	private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");

	private static final DayHours DAY = new DayHours("06:00", "23:00");

	static VenueDetails details(List<DayHours> hours, PitchDetails... pitches) {
		return new VenueDetails("Osu Astro Turf", "Osu, Accra", null, null, "024 410 0200", List.of(pitches), hours,
				List.of("floodlights"));
	}

	static List<DayHours> everyDay(DayHours hours) {
		return Collections.nCopies(7, hours);
	}

	static PitchDetails pitch(String name) {
		return new PitchDetails(null, name, "football", "5-a-side", "turf", 25_000);
	}

	private final Venue venue = new Venue(UUID.randomUUID(), Market.get("GH"));

	@Test
	void aVenueIsListedInItsMarketWithItsSports() {
		venue.apply(details(everyDay(DAY), pitch("Pitch A"), new PitchDetails(null, "Court", "tennis", "Singles", "hard", 12_000)), NOW);
		assertThat(venue.isListed()).isTrue();
		assertThat(venue.getCurrency()).isEqualTo("GHS");
		assertThat(venue.getPhone()).isEqualTo("+233244100200");
		assertThat(venue.sports()).containsExactly("football", "tennis");
	}

	@Test
	void theFormsMistakesGetMessagesForPeople() {
		assertThatThrownBy(() -> venue.apply(details(everyDay(DAY)), NOW)).hasMessage("Add at least one pitch or court.");
		var closed = new ArrayList<DayHours>(Collections.nCopies(7, null));
		assertThatThrownBy(() -> venue.apply(details(closed, pitch("A")), NOW)).hasMessage("Open at least one day a week.");
		assertThatThrownBy(() -> venue.apply(details(everyDay(DAY), pitch(" ")), NOW)).hasMessage("Every pitch needs a name.");
		assertThatThrownBy(() -> venue.apply(details(everyDay(DAY), new PitchDetails(null, "A", "football", "Doubles", "turf", 1)), NOW))
			.hasMessage("Pick a sport and format for every pitch.");
		assertThatThrownBy(() -> venue.apply(details(everyDay(DAY), new PitchDetails(null, "A", "football", "5-a-side", "turf", 0)), NOW))
			.hasMessage("Every pitch needs a price per hour.");
		assertThatThrownBy(() -> new DayHours("22:00", "22:30"))
			.hasMessage("Each open day needs at least an hour between opening and closing.");
		assertThatThrownBy(() -> new DayHours("6am", "22:00")).isInstanceOf(BusinessException.class);
	}

	@Test
	void openingHoursAreTheVenuesLocalTimeAndCanRunToMidnight() {
		venue.apply(details(everyDay(new DayHours("06:00", "24:00")), pitch("A")), NOW);
		// Accra is on UTC all year, so local and UTC times match here.
		assertThat(venue.isOpen(Instant.parse("2026-09-26T06:00:00Z"), Instant.parse("2026-09-26T07:00:00Z"))).isTrue();
		assertThat(venue.isOpen(Instant.parse("2026-09-26T05:00:00Z"), Instant.parse("2026-09-26T06:00:00Z"))).isFalse();
		assertThat(venue.isOpen(Instant.parse("2026-09-26T23:00:00Z"), Instant.parse("2026-09-27T00:00:00Z"))).as("until midnight").isTrue();
		assertThat(venue.isOpen(Instant.parse("2026-09-26T23:30:00Z"), Instant.parse("2026-09-27T00:30:00Z"))).as("past midnight").isFalse();
	}

	@Test
	void closedDaysAreClosed() {
		var hours = new ArrayList<>(everyDay(DAY));
		hours.set(0, null); // Sundays
		venue.apply(details(hours, pitch("A")), NOW);
		assertThat(venue.isOpen(Instant.parse("2026-09-27T10:00:00Z"), Instant.parse("2026-09-27T11:00:00Z"))).isFalse();
		assertThat(venue.hours().getFirst()).isNull();
	}

	@Test
	void aPitchCostsItsHourlyPriceForTheTimeBooked() {
		venue.apply(details(everyDay(DAY), pitch("A")), NOW);
		var pitch = venue.activePitches().getFirst();
		assertThat(pitch.priceFor(60)).isEqualTo(25_000);
		assertThat(pitch.priceFor(90)).isEqualTo(37_500);
	}

}
