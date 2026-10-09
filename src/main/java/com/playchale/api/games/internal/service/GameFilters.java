package com.playchale.api.games.internal.service;

/**
 * Discover's filters, as the web app's GameFilters.
 *
 * @param sport   a sport id, or "all" / null for any
 * @param when    "any", "today", "tomorrow" or "weekend", on the viewer's clock
 * @param country where the games are (ISO 3166-1), or null / "" for everywhere
 * @param zone    the viewer's timezone (IANA), for what "today" means; Ghana's when left out
 * @param near    "lat,lng": nearest first from there, each with how far it is; left out, soonest first
 */
public record GameFilters(String query, String sport, String when, String country, String zone, String near) {

	public GameFilters(String query, String sport, String when) {
		this(query, sport, when, null, null, null);
	}

	public GameFilters(String query, String sport, String when, String country, String zone) {
		this(query, sport, when, country, zone, null);
	}

}
