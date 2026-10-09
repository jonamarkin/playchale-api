package com.playchale.api.shared.maps;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The web app's MapPin: where something is on the map, sent with a venue, game or event and shown
 * back with it. {@code source} is {@code "place"} (the place's own coordinates, from Google's place
 * search) or {@code "own"} (a spot someone set), as {@link Pin}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MapPin(Double lat, Double lng, String placeId, String source) {
}
