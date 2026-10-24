package com.playchale.api.games.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Games that have been played but have no result yet, for reminding the host to add the score. */
public interface EndedGames {

	/** A game whose time has passed with nothing recorded. {@code endsAt} is kick-off plus its length. */
	record Ended(UUID id, String title, UUID hostId, Instant startsAt, Instant endsAt, String timezone) {
	}

	/** Games not called off and without a result, not a competition's, that ended from {@code from} until {@code to}, earliest first. */
	List<Ended> endedBetween(Instant from, Instant to);

}
