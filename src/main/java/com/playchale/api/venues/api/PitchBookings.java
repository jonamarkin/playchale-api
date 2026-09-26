package com.playchale.api.venues.api;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** How the games module finds partner venues and holds their pitches for games. */
public interface PitchBookings {

	Optional<VenueSummary> findVenue(UUID venueId);

	/**
	 * The map links of these venues, for directions to games and leagues there. Venues without one
	 * are left out. Looked up each time, so a pin the owner adds later reaches every game.
	 */
	Map<UUID, String> mapLinks(Collection<UUID> venueIds);

	/**
	 * Holds a pitch for a game, within opening hours and clash-free. Refuses with a message for the
	 * host when it can't.
	 */
	PitchBooking bookForGame(UUID venueId, UUID pitchId, Instant startsAt, Instant endsAt, UUID gameId, UUID host);

	/** Releases whatever a game holds, e.g. when it's called off. */
	void releaseForGame(UUID gameId);

}
