package com.playchale.api.competitions.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A competition as its organiser describes it: the web app's NewCompetitionInput.
 *
 * @param venueKind   "listed" (a partner venue, {@code venueId} set) or "unlisted" (any name)
 * @param playerLists "expected" (teams of players; the default when left out) or "optional" (schools or
 *                    organisations, whose teams may list no players)
 */
public record CompetitionDetails(String name, String sport, String format, String venueKind, UUID venueId, String venueName,
		String venueArea, String venueMapUrl, Instant startsAt, int durationMinutes, String playerLists) {
}
