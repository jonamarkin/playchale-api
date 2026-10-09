package com.playchale.api.venues.api;

import com.playchale.api.shared.maps.Pin;

/**
 * Where a partner venue is, for games and leagues there: its link for directions and its pin on the
 * map. Either may be null.
 */
public record VenueLocation(String mapUrl, Pin pin) {
}
