package com.playchale.api.venues.internal.domain;

import java.util.List;

/**
 * A venue as the owner describes it, from the venue form.
 *
 * @param hours seven days, Sunday first; null for a closed day
 */
public record VenueDetails(String name, String area, String description, String address, String phone,
		List<PitchDetails> pitches, List<DayHours> hours, List<String> amenities) {
}
