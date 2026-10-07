package com.playchale.api.games.internal.domain;

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

	/** The same, called something else: a friendly is named after its teams when the host leaves it blank. */
	public GameDetails withTitle(String title) {
		return new GameDetails(sport, format, title, startsAt, durationMinutes, venueKind, venueId, pitchId, venueName, venueArea, venueMapUrl,
				capacity, totalCost, pricing, visibility, notes);
	}

	/** The same game at another time: how a host repeats last week's. */
	public GameDetails startingAt(Instant startsAt) {
		return new GameDetails(sport, format, title, startsAt, durationMinutes, venueKind, venueId, pitchId, venueName, venueArea, venueMapUrl,
				capacity, totalCost, pricing, visibility, notes);
	}

}
