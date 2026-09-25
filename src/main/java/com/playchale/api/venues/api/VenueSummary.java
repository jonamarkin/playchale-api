package com.playchale.api.venues.api;

import java.util.UUID;

/** A venue in brief, for other modules showing where something is. */
public record VenueSummary(UUID id, String name, String area, UUID ownerId) {
}
