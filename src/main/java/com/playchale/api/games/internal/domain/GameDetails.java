package com.playchale.api.games.internal.domain;

import java.time.Instant;
import java.util.UUID;

import com.playchale.api.shared.maps.Pin;

/**
 * A game as its host describes it, from the create form.
 *
 * @param venueKind "listed" (a partner venue, {@code venueId} set, maybe {@code pitchId}) or
 *                  "unlisted" (any place: {@code venueName}, maybe {@code venueArea} and a
 *                  {@code venueMapUrl} for directions)
 * @param totalCost the whole cost, minor units; 0 for a free game
 * @param pricing   "split" (the total shared by the spots) or "per-player" (the total is the price
 *                  times the spots); null means "split"
 * @param venuePin  where an unlisted venue is on the map: as the app sent it ({@link Pin#sent}), or
 *                  as it was kept on the game this one repeats
 */
public record GameDetails(String sport, String format, String title, Instant startsAt, int durationMinutes,
		String venueKind, UUID venueId, UUID pitchId, String venueName, String venueArea, String venueMapUrl, int capacity, long totalCost,
		String pricing, String visibility, String notes, Pin venuePin) {

	/** Without a pin. */
	public GameDetails(String sport, String format, String title, Instant startsAt, int durationMinutes, String venueKind, UUID venueId,
			UUID pitchId, String venueName, String venueArea, String venueMapUrl, int capacity, long totalCost, String pricing, String visibility,
			String notes) {
		this(sport, format, title, startsAt, durationMinutes, venueKind, venueId, pitchId, venueName, venueArea, venueMapUrl, capacity, totalCost,
				pricing, visibility, notes, null);
	}

	/** The same, called something else: a friendly is named after its teams when the host leaves it blank. */
	public GameDetails withTitle(String title) {
		return new GameDetails(sport, format, title, startsAt, durationMinutes, venueKind, venueId, pitchId, venueName, venueArea, venueMapUrl,
				capacity, totalCost, pricing, visibility, notes, venuePin);
	}

	/** The same game at another time: how a host repeats last week's. */
	public GameDetails startingAt(Instant startsAt) {
		return new GameDetails(sport, format, title, startsAt, durationMinutes, venueKind, venueId, pitchId, venueName, venueArea, venueMapUrl,
				capacity, totalCost, pricing, visibility, notes, venuePin);
	}

}
