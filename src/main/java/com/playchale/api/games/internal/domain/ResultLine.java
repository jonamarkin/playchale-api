package com.playchale.api.games.internal.domain;

import java.util.UUID;

import jakarta.persistence.Embeddable;

/**
 * One person in a result: their side ("home", "away" or "absent") and what they scored.
 *
 * @param playerKey the roster's key: a player's ID, or "guest:<spot>"
 * @param userId    null for a guest, who earns nobody a record
 */
@Embeddable
public record ResultLine(String playerKey, UUID userId, String side, int goals, int assists, int points) {

	public static final String HOME = "home";

	public static final String AWAY = "away";

	public static final String ABSENT = "absent";

	public boolean played() {
		return HOME.equals(side) || AWAY.equals(side);
	}

}
