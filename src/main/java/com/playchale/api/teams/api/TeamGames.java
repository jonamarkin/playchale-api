package com.playchale.api.teams.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A team's games — league fixtures and friendlies — for its page: its record, what's coming up and
 * its latest results. Declared here and implemented by the games module, so teams doesn't depend on
 * games (which depends on teams).
 */
public interface TeamGames {

	/** Played fixtures and friendlies with a result. */
	record Record(int played, int won, int drawn, int lost) {
	}

	/**
	 * One of the team's games, from the team's side.
	 *
	 * @param competitionId set for a league fixture
	 * @param challenge     a friendly's challenge: "pending" until the other captain answers, then "accepted"; null for fixtures
	 * @param outcome       "W", "D" or "L" once played, with the scores from the team's side
	 */
	record TeamGame(UUID gameId, String title, Instant startsAt, UUID opponentId, String opponentName, boolean home, UUID competitionId,
			String challenge, Integer scoreFor, Integer scoreAgainst, String outcome) {
	}

	/** @param upcoming soonest first; @param recent latest first */
	record Summary(Record record, List<TeamGame> upcoming, List<TeamGame> recent) {
	}

	Summary of(UUID teamId);

}
