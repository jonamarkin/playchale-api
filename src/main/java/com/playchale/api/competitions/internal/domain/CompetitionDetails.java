package com.playchale.api.competitions.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A competition as its organiser describes it: the web app's NewCompetitionInput.
 *
 * @param venueKind   "listed" (a partner venue, {@code venueId} set) or "unlisted" (any name)
 * @param playerLists "expected" (teams of players; the default when left out) or "optional" (schools or
 *                    organisations, whose teams may list no players)
 * @param timezone    where it's played, as the organiser's app sends it (IANA), for a typed-in place;
 *                    a partner venue's is its own
 * @param structure   "league" (everyone plays everyone; the default when left out) or "knockout" 
 */
public record CompetitionDetails(String name, String sport, String format, String venueKind, UUID venueId, String venueName,
		String venueArea, String venueMapUrl, Instant startsAt, int durationMinutes, String playerLists, String timezone,
		String structure) {

	public CompetitionDetails(String name, String sport, String format, String venueKind, UUID venueId, String venueName,
			String venueArea, String venueMapUrl, Instant startsAt, int durationMinutes, String playerLists) {
		this(name, sport, format, venueKind, venueId, venueName, venueArea, venueMapUrl, startsAt, durationMinutes, playerLists, null, null);
	}

	public CompetitionDetails(String name, String sport, String format, String venueKind, UUID venueId, String venueName,
			String venueArea, String venueMapUrl, Instant startsAt, int durationMinutes, String playerLists, String timezone) {
		this(name, sport, format, venueKind, venueId, venueName, venueArea, venueMapUrl, startsAt, durationMinutes, playerLists, timezone, null);
	}

}
