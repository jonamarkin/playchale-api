package com.playchale.api.games.internal.service;

/**
 * Discover's filters, as the web app's GameFilters.
 *
 * @param sport   a sport id, or "all" / null for any
 * @param when    "any", "today", "tomorrow" or "weekend", on the viewer's clock
 * @param country where the games are (ISO 3166-1), or null / "" for everywhere
 * @param zone    the viewer's timezone (IANA), for what "today" means; Ghana's when left out
 */
public record GameFilters(String query, String sport, String when, String country, String zone) {

	public GameFilters(String query, String sport, String when) {
		this(query, sport, when, null, null);
	}

}
