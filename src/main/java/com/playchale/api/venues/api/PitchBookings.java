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
	 * Where these venues are (link for directions, pin on the map), for games and leagues there.
	 * Venues with neither are left out. Looked up each time, so a pin the owner adds later reaches
	 * every game.
	 */
	Map<UUID, VenueLocation> locations(Collection<UUID> venueIds);

	/**
	 * Holds a pitch for a game, within opening hours and clash-free. Refuses with a message for the
	 * host when it can't.
	 */
	/** Checks the same venue, opening-hours and clash rules without creating a booking. */
	Optional<String> problemForGame(UUID venueId, UUID pitchId, Instant startsAt, Instant endsAt, UUID gameId);

	PitchBooking bookForGame(UUID venueId, UUID pitchId, Instant startsAt, Instant endsAt, UUID gameId, UUID host);

	/** Releases whatever a game holds, e.g. when it's called off. */
	void releaseForGame(UUID gameId);

}
