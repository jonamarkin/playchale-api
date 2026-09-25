package com.playchale.api.venues.api;

import java.util.UUID;

/**
 * A pitch booked for a game.
 *
 * @param price what the slot costs, in the venue's currency, minor units
 */
public record PitchBooking(UUID bookingId, UUID venueId, String venueName, String venueArea, UUID ownerId, UUID pitchId,
		String pitchName, long price) {
}
