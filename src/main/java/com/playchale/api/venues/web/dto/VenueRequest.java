package com.playchale.api.venues.web.dto;

import java.util.List;
import java.util.UUID;

import com.playchale.api.venues.internal.domain.DayHours;
import com.playchale.api.venues.internal.domain.PitchDetails;
import com.playchale.api.venues.internal.domain.VenueDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** The web app's VenueInput. Closed days are null in {@code hours}. */
public record VenueRequest(String name, String area, String description, String address, String phone,
		@NotNull(message = "Add at least one pitch or court.") List<@Valid PitchRequest> pitches,
		@NotNull(message = "Add your opening hours.") List<HoursRequest> hours, List<String> amenities) {

	/** Existing pitches keep their id, so their bookings stay attached; new ones leave it out. */
	public record PitchRequest(UUID id, String name, String sport, String format, String surface, long pricePerHour) {
	}

	public record HoursRequest(String open, String close) {
	}

	public VenueDetails toDetails() {
		return new VenueDetails(name, area, description, address, phone,
				pitches.stream().map(p -> new PitchDetails(p.id(), p.name(), p.sport(), p.format(), p.surface(), p.pricePerHour())).toList(),
				hours.stream().map(h -> h == null ? null : new DayHours(h.open(), h.close())).toList(), amenities);
	}

}
