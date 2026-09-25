package com.playchale.api.games.internal.service;

/**
 * Discover's filters, as the web app's GameFilters.
 *
 * @param sport a sport id, or "all" / null for any
 * @param when  "any", "today", "tomorrow" or "weekend", in the market's local time
 */
public record GameFilters(String query, String sport, String when) {
}
