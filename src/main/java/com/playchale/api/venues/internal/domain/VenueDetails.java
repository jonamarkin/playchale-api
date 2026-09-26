package com.playchale.api.venues.internal.domain;

import java.util.List;

/**
 * A venue as the owner describes it, from the venue form.
 *
 * @param hours  seven days, Sunday first; null for a closed day
 * @param mapUrl where it is on a map: a Google Maps, Apple Maps or Waze link, or coordinates
 */
public record VenueDetails(String name, String area, String description, String address, String mapUrl, String phone,
		List<PitchDetails> pitches, List<DayHours> hours, List<String> amenities) {
}
