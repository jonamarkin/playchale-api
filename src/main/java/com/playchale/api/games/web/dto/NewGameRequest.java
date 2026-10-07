package com.playchale.api.games.web.dto;

import java.time.Instant;
import java.util.UUID;

import com.playchale.api.games.internal.domain.GameDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * The web app's NewGameInput. With {@code homeTeamId} (a team the host captains) and
 * {@code awayTeamId} (the team they're challenging) it's a friendly between the two.
 * {@code timezone} is where a game at a typed-in place is played (IANA), as the host's app sends it; a
 * partner venue's is its own. With {@code repeats} it's the first game of a repeating one.
 */
public record NewGameRequest(String sport, String format, String title, @NotNull(message = "Pick a time in the future.") Instant startsAt,
		int durationMinutes, @NotNull(message = "Say where you’re playing.") @Valid VenueRefRequest venue, int capacity,
		long totalCost, String pricing, String visibility, String notes, UUID homeTeamId, UUID awayTeamId, String timezone,
		GameRequests.RepeatsRequest repeats) {

	/**
	 * The web app's VenueRef: {kind: "listed", venueId, name, area, pitchId?, pitchName?} or {kind: "unlisted", name, area?,
	 * mapUrl?}. A listed venue's map link is its own, so one sent with it is ignored.
	 */
	public record VenueRefRequest(String kind, UUID venueId, String name, String area, UUID pitchId, String pitchName, String mapUrl) {
	}

	public GameDetails toDetails() {
		return new GameDetails(sport, format, title, startsAt, durationMinutes, "listed".equals(venue.kind()) ? "listed" : "unlisted",
				venue.venueId(), venue.pitchId(), venue.name(), venue.area(), venue.mapUrl(), capacity, totalCost, pricing, visibility, notes);
	}

}
