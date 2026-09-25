package com.playchale.api.games.internal.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A game as its host describes it, from the create form.
 *
 * @param venueKind "listed" (a partner venue, {@code venueId} set, maybe {@code pitchId}) or
 *                  "unlisted" (any place: {@code venueName} and maybe {@code venueArea})
 * @param totalCost the whole cost to share, minor units; 0 for a free game
 */
public record GameDetails(String sport, String format, String title, Instant startsAt, int durationMinutes,
		String venueKind, UUID venueId, UUID pitchId, String venueName, String venueArea, int capacity, long totalCost,
		String visibility, String notes) {

	/** The same game a week later: how a host repeats last week's. */
	public GameDetails weekLater() {
		return new GameDetails(sport, format, title, startsAt.plus(Duration.ofDays(7)), durationMinutes, venueKind,
				venueId, pitchId, venueName, venueArea, capacity, totalCost, visibility, notes);
	}

}
