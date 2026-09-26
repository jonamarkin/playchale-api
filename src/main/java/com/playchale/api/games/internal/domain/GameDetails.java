package com.playchale.api.games.internal.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A game as its host describes it, from the create form.
 *
 * @param venueKind "listed" (a partner venue, {@code venueId} set, maybe {@code pitchId}) or
 *                  "unlisted" (any place: {@code venueName}, maybe {@code venueArea} and a
 *                  {@code venueMapUrl} for directions)
 * @param totalCost the whole cost, minor units; 0 for a free game
 * @param pricing   "split" (the total shared by the spots) or "per-player" (the total is the price
 *                  times the spots); null means "split"
 */
public record GameDetails(String sport, String format, String title, Instant startsAt, int durationMinutes,
		String venueKind, UUID venueId, UUID pitchId, String venueName, String venueArea, String venueMapUrl, int capacity, long totalCost,
		String pricing, String visibility, String notes) {

	/** The same game a week later: how a host repeats last week's. */
	public GameDetails weekLater() {
		return new GameDetails(sport, format, title, startsAt.plus(Duration.ofDays(7)), durationMinutes, venueKind,
				venueId, pitchId, venueName, venueArea, venueMapUrl, capacity, totalCost, pricing, visibility, notes);
	}

}
