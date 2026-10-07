package com.playchale.api.games.api;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A repeating game, as its host manages it: what its games are set up from, when they're played, and
 * where it has got to. {@code weekday} is ISO (1 is Monday) and {@code kickOff} "18:00" in
 * {@code timezone}; {@code weekOfMonth} (1 to 4, or -1 for the last) is set for a monthly one.
 * {@code status} is "active", "paused" (with {@code pausedReason}) or "stopped". {@code lastGame} is
 * its latest game, still to come or already played.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SeriesResponse(UUID id, String title, String sport, String format, String venueKind, UUID venueId, UUID pitchId,
		String venueName, String venueArea, int durationMinutes, int capacity, long totalCost, String pricing, String currency, String visibility,
		String notes, String country, String timezone, String frequency, int weekday, Integer weekOfMonth, String kickOff, String status,
		String pausedReason, Instant nextStartsAt, Instant opensAt, LastGame lastGame) {

	public record LastGame(UUID id, Instant startsAt, String status) {
	}

}
