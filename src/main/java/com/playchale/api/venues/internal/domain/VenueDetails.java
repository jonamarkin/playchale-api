package com.playchale.api.venues.internal.domain;

import java.util.List;

import com.playchale.api.shared.maps.MapPin;

/**
 * A venue as the owner describes it, from the venue form.
 *
 * @param hours  seven days, Sunday first; null for a closed day
 * @param mapUrl where it is on a map: a Google Maps, Apple Maps or Waze link, or coordinates. A
 *               {@code pin} takes its place
 * @param pin    where it is on the map, or null for none
 */
public record VenueDetails(String name, String area, String description, String address, String mapUrl, MapPin pin, String phone,
		List<PitchDetails> pitches, List<DayHours> hours, List<String> amenities) {

	/** Without a pin. */
	public VenueDetails(String name, String area, String description, String address, String mapUrl, String phone, List<PitchDetails> pitches,
			List<DayHours> hours, List<String> amenities) {
		this(name, area, description, address, mapUrl, null, phone, pitches, hours, amenities);
	}

}
