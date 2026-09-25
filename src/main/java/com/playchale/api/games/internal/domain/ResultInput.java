package com.playchale.api.games.internal.domain;

import java.util.List;

/**
 * A result as the host enters it, the web app's ResultInput. Players are named by the roster's
 * keys: a player's ID, or "guest:<spot>". "Home" is simply the host's side.
 *
 * @param sets   set-based sports only; the match score is then worked out from them
 * @param absent players who joined but didn't turn up
 */
public record ResultInput(int homeScore, int awayScore, List<String> home, List<String> away, List<Scorer> scorers,
		List<SetScore> sets, List<String> absent) {

	/** One player's numbers in the game. Only the sport's own stats are kept. */
	public record Scorer(String userId, Integer goals, Integer assists, Integer points) {

		int stat(String key) {
			var value = switch (key) {
				case "goals" -> goals;
				case "assists" -> assists;
				case "points" -> points;
				default -> null;
			};
			return value == null ? 0 : value;
		}

	}

}
