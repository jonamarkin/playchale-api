package com.playchale.api.games.api;

import java.util.List;
import java.util.UUID;

/** Records a trusted competition result through the same standings and profile pipeline as games. */
public interface OfficialResults {

	record Player(UUID userId, String side, int goals, int assists) {
	}

	void record(UUID gameId, int homeScore, int awayScore, List<Player> players, UUID actorId);
}
