package com.playchale.api.games.internal.service;

import java.util.UUID;

import com.playchale.api.games.api.GameModeration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** GameModeration, for the admin desk: the work is GameService's, alongside calling a game off. */
@Service
class GameModerationService implements GameModeration {

	private final GameService games;

	GameModerationService(GameService games) {
		this.games = games;
	}

	@Override
	@Transactional
	public void takeDown(UUID gameId, String why, UUID staffId) {
		games.takeDown(gameId, why, staffId);
	}

}
