package com.playchale.api.venues.internal.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.playchale.api.users.api.UserSummary;
import com.playchale.api.venues.internal.domain.DayHours;
import com.playchale.api.venues.internal.domain.Pitch;
import com.playchale.api.venues.internal.domain.Venue;

/**
 * A venue as the web app's Venue type, or VenueView when {@code owner} is filled in. Closed days are
 * null in {@code hours}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VenueResponse(UUID id, String name, String area, List<String> sports, boolean listed, UUID ownerId,
		String description, String address, String phone, List<PitchResponse> pitches,
		@JsonInclude(JsonInclude.Include.ALWAYS) List<DayHours> hours, List<String> amenities, Instant createdAt,
		UserSummary owner) {

	public record PitchResponse(UUID id, String name, String sport, String format, String surface, long pricePerHour) {

		static PitchResponse of(Pitch p) {
			return new PitchResponse(p.getId(), p.getName(), p.getSport(), p.getFormat(), p.getSurface(), p.getPricePerHour());
		}

	}

	static VenueResponse of(Venue v, UserSummary owner) {
		return new VenueResponse(v.getId(), v.getName(), v.getArea(), v.sports(), v.isListed(), v.getOwnerId(),
				v.getDescription(), v.getAddress(), v.getPhone(), v.activePitches().stream().map(PitchResponse::of).toList(),
				v.hours(), v.getAmenities(), v.getCreatedAt(), owner);
	}

}
