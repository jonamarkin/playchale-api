package com.playchale.api.payments.api;

import java.util.UUID;

/**
 * A player's in-app payment of their share went through.
 *
 * @param paid how many of the game's {@code players} have paid now
 */
public record PaymentReceived(UUID gameId, String gameTitle, UUID hostId, UUID payerId, long amount, String currency, int paid,
		int players) {
}
