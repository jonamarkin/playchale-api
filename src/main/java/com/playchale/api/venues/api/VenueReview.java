package com.playchale.api.venues.api;

import java.util.UUID;

/**
 * PlayChale's staff checking venues, from the admin desk: anyone can list one, so a new venue waits
 * until staff approve it before players can find or book it. Declining tells the owner what to
 * change; their change sends it back. A live venue can be declined too, which takes it off.
 */
public interface VenueReview {

	/** Players can find it and book it from now. Its owner is told. */
	void approve(UUID venueId, UUID staffId);

	/** Hidden from players until the owner changes it. {@code note}, what to change, is required: the owner reads it. */
	void decline(UUID venueId, String note, UUID staffId);

}
