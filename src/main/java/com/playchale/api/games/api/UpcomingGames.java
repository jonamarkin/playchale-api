package com.playchale.api.games.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Games still to be played and who's in them, for reminding them the evening before. */
public interface UpcomingGames {

	/**
	 * A game still on.
	 *
	 * @param capacity    null for a game with no limit
	 * @param filled      how many are in, guests too
	 * @param playerIds   everyone in it with an account, the host too
	 * @param guestPhones the numbers guests took or were given a spot under (E.164), where there is one
	 */
	record Upcoming(UUID id, String title, String venueName, String country, Instant startsAt, String timezone, Integer capacity, int filled,
			UUID hostId, List<UUID> playerIds, List<String> guestPhones) {
	}

	/** Games not called off or played, and not a competition's, starting from {@code from} until {@code to}, soonest first. */
	List<Upcoming> startingBetween(Instant from, Instant to);

}
