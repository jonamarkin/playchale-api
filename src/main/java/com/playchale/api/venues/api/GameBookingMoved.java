package com.playchale.api.venues.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A venue's manager moved a game's pitch booking: to another pitch, another time, or both. Published
 * inside the move's transaction, so the game (games module) moves with it or neither does.
 *
 * @param startsAt the game's new kick-off; the length stays the same
 */
public record GameBookingMoved(UUID gameId, UUID venueId, String venueName, UUID pitchId, String pitchName, Instant startsAt,
		Instant endsAt) {
}
