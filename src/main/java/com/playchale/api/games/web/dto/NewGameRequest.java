package com.playchale.api.games.web.dto;

import java.time.Instant;
import java.util.UUID;

import com.playchale.api.games.internal.domain.GameDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** The web app's NewGameInput. */
public record NewGameRequest(String sport, String format, String title, @NotNull(message = "Pick a time in the future.") Instant startsAt,
		int durationMinutes, @NotNull(message = "Say where you’re playing.") @Valid VenueRefRequest venue, int capacity,
		long totalCost, String visibility, String notes) {

	/** The web app's VenueRef: {kind: "listed", venueId, name, area, pitchId?, pitchName?} or {kind: "unlisted", name, area?}. */
	public record VenueRefRequest(String kind, UUID venueId, String name, String area, UUID pitchId, String pitchName) {
	}

	public GameDetails toDetails() {
		return new GameDetails(sport, format, title, startsAt, durationMinutes, "listed".equals(venue.kind()) ? "listed" : "unlisted",
				venue.venueId(), venue.pitchId(), venue.name(), venue.area(), capacity, totalCost, visibility, notes);
	}

}
