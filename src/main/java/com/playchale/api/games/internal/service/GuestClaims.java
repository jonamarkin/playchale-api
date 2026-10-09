package com.playchale.api.games.internal.service;

import com.playchale.api.auth.api.ContactVerified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Guest spots becoming their player's: when someone signs in with the number or address a spot was
 * taken or held under, it's theirs, and so is the result of a game they already played as a guest.
 *
 * <p>After the sign-in commits, in a transaction of its own ({@link GameService#claimHeldFor}), so a
 * spot that can't be claimed never stops anyone signing in. It still runs before the sign-in's response goes back, so the games are
 * in My games by the time the app asks.
 */
@Component
class GuestClaims {

	private static final Logger log = LoggerFactory.getLogger(GuestClaims.class);

	private final GameService games;

	GuestClaims(GameService games) {
		this.games = games;
	}

	@TransactionalEventListener(fallbackExecution = true)
	void on(ContactVerified e) {
		try {
			var claimed = games.claimHeldFor(e.userId(), e.phone(), e.email());
			if (claimed > 0) {
				log.info("Gave {} guest spot(s) to the player who signed in with them", claimed);
			}
		}
		catch (RuntimeException failed) {
			log.warn("Couldn’t give guest spots to {}: {}", e.userId(), failed.toString());
		}
	}

}
