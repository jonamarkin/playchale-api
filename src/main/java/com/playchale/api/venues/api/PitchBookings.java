package com.playchale.api.venues.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** How the games module finds partner venues and holds their pitches for games. */
public interface PitchBookings {

	Optional<VenueSummary> findVenue(UUID venueId);

	/**
	 * Holds a pitch for a game, within opening hours and clash-free. Refuses with a message for the
	 * host when it can't.
	 */
	PitchBooking bookForGame(UUID venueId, UUID pitchId, Instant startsAt, Instant endsAt, UUID gameId, UUID host);

	/** Releases whatever a game holds, e.g. when it's called off. */
	void releaseForGame(UUID gameId);

}
