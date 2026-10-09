package com.playchale.api.shared.maps;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The web app's MapPin: where something is on the map, sent with a venue, game or event and shown
 * back with it. {@code source} is {@code "place"} (the place's own coordinates, from Google's place
 * search) or {@code "own"} (a spot someone set), as {@link Pin}.
 *
 * @param pinnedAt for a place's coordinates, when they were last looked up with Google. Shown with
 *                 them, and sent back with a place found on PlayChale, so they're never kept longer
 *                 than Google allows by being copied to a new game. Left out, they're new: now
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MapPin(Double lat, Double lng, String placeId, String source, Instant pinnedAt) {

	public MapPin(Double lat, Double lng, String placeId, String source) {
		this(lat, lng, placeId, source, null);
	}

}
