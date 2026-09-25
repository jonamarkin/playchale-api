package com.playchale.api.games.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** What the payments module needs from games: who owes what, and recording a share paid in the app. */
public interface GameShares {

	/** A player's share of a game, in its currency's minor units. */
	record ShareDue(UUID gameId, String title, UUID hostId, long amount, String currency) {
	}

	/** After a share is paid: how many of the roster have paid now. */
	record SharePaid(UUID gameId, String title, UUID hostId, int paid, int players) {
	}

	/**
	 * What the player owes for a game. Refused if they aren't in it, it's free, it was called off, or
	 * they've paid already.
	 */
	ShareDue shareDue(UUID gameId, UUID userId);

	/** Marks the player's share paid by an in-app payment. */
	SharePaid markPaidInApp(UUID gameId, UUID userId, UUID paymentId);

	/** Game titles, for statements. Games that don't exist are missing from the map. */
	Map<UUID, String> titles(Collection<UUID> gameIds);

}
