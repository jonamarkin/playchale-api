package com.playchale.api.venues.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * What a venue owner's schedule shows about the games booked in it. Declared here and implemented
 * by the games module, so venues can show game titles without depending on games (which depends on
 * venues). Until games provides one, the schedule simply leaves the titles out.
 */
public interface BookedGames {

	record BookedGame(String title, int players) {
	}

	Map<UUID, BookedGame> describe(Collection<UUID> gameIds);

}
