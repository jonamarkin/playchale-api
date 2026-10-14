package com.playchale.api.games.api;

import java.util.UUID;

/**
 * What PlayChale's own staff can do to a game, from the admin desk: take down one that shouldn't
 * be there (a fake, spam) when its host won't. Its host and players are told why.
 */
public interface GameModeration {

	/**
	 * Calls the game off on PlayChale's behalf, and stops it repeating. Refused for a competition
	 * fixture (the competition's to change), a game already off or played, and one someone has paid
	 * into (refunds aren't in the app). {@code why} is required: the host and players read it.
	 */
	void takeDown(UUID gameId, String why, UUID staffId);

}
