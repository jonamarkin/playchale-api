package com.playchale.api.venues.api;

import java.util.UUID;

/**
 * A venue in brief, for other modules showing where something is. A game or league there takes its
 * country (money, phone numbers) and timezone (kick-off).
 */
public record VenueSummary(UUID id, String name, String area, UUID ownerId, String country, String currency, String timezone) {
}
