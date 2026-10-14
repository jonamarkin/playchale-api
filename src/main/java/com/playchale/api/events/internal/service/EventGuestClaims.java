package com.playchale.api.events.internal.service;

import com.playchale.api.auth.api.ContactVerified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Places at a games day becoming their player's: when someone signs in with the number they signed
 * up with as a guest, the place is theirs, with its games. After the sign-in commits, in a
 * transaction of its own ({@link EventGuestService#claimHeldFor}), so a place that can't move never
 * stops anyone signing in. Just like guest spots in a game (games' GuestClaims).
 */
@Component
class EventGuestClaims {

	private static final Logger log = LoggerFactory.getLogger(EventGuestClaims.class);

	private final EventGuestService guests;

	EventGuestClaims(EventGuestService guests) {
		this.guests = guests;
	}

	@TransactionalEventListener(fallbackExecution = true)
	void on(ContactVerified e) {
		try {
			var claimed = guests.claimHeldFor(e.userId(), e.phone());
			if (claimed > 0) {
				log.info("Gave {} games-day place(s) to the player who signed in with their number", claimed);
			}
		}
		catch (RuntimeException failed) {
			log.warn("Couldn’t give games-day places to {}: {}", e.userId(), failed.toString());
		}
	}

}
