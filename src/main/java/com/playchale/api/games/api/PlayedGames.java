package com.playchale.api.games.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A player's record: the games with results they played in. For profiles and anything else that needs stats. */
public interface PlayedGames {

	/**
	 * One game from the player's side.
	 *
	 * @param scoreFor their side's score (sets won, for set-based sports)
	 * @param sets     set by set with their side first; empty unless the sport is played in sets
	 */
	record PlayedGame(UUID gameId, String title, String sport, String format, Instant startsAt, String venueName, int scoreFor,
			int scoreAgainst, int goals, int assists, int points, List<Set> sets) {

		/** "W", "D" or "L". */
		public String outcome() {
			return scoreFor == scoreAgainst ? "D" : scoreFor > scoreAgainst ? "W" : "L";
		}

		public int setsWon() {
			return (int) sets.stream().filter(s -> s.home() > s.away()).count();
		}

	}

	/** One set's score, the player's side first. */
	record Set(int home, int away) {
	}

	/** Games with a result that the player was on a side in (not absent), newest first. */
	List<PlayedGame> by(UUID userId);

}
