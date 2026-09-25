package com.playchale.api.competitions.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A competition as its organiser describes it: the web app's NewCompetitionInput.
 *
 * @param venueKind "listed" (a partner venue, {@code venueId} set) or "unlisted" (any name)
 */
public record CompetitionDetails(String name, String sport, String format, String venueKind, UUID venueId, String venueName,
		String venueArea, Instant startsAt, int durationMinutes) {
}
