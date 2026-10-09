package com.playchale.api.games.web.dto;

import java.time.Instant;

import com.playchale.api.games.internal.service.GameService;
import com.playchale.api.shared.maps.MapPin;
import jakarta.validation.constraints.NotNull;

/**
 * The web app's GameChanges: everything the host can change about a game, sent whole. {@code venue}
 * is where a game at a typed-in place is now; a partner venue's game leaves it out.
 */
public record GameChangeRequest(String format, String title, String notes, @NotNull(message = "Pick a time in the future.") Instant startsAt,
		int durationMinutes, int capacity, long totalCost, String pricing, String visibility, VenueChange venue) {

	public record VenueChange(String name, String area, String mapUrl, MapPin pin) {
	}

	public GameService.Change toChange() {
		return new GameService.Change(format, title, notes, startsAt, durationMinutes, capacity, totalCost, pricing, visibility,
				venue == null ? null : new GameService.VenueChange(venue.name(), venue.area(), venue.mapUrl(), venue.pin()));
	}

}
