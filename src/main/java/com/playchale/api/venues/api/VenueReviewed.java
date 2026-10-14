package com.playchale.api.venues.api;

import java.util.UUID;

/**
 * Staff checked a venue (VenueReview). Its owner hears either way.
 *
 * @param approved  live for players now; otherwise declined, with {@code note}
 * @param wasLive   it was live before: declining it took it off
 */
public record VenueReviewed(UUID venueId, String name, UUID ownerId, boolean approved, String note, boolean wasLive) {
}
