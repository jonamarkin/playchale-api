package com.playchale.api.games.internal.service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.playchale.api.games.api.GameShares;
import com.playchale.api.games.internal.domain.Game;
import com.playchale.api.games.internal.repository.GameRepository;
import com.playchale.api.market.Market;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Serves {@link GameShares} to the payments module. */
@Service
class GameSharesService implements GameShares {

	private final GameRepository games;

	GameSharesService(GameRepository games) {
		this.games = games;
	}

	@Override
	@Transactional(readOnly = true)
	public ShareDue shareDue(UUID gameId, UUID userId) {
		var game = games.findById(gameId).orElseThrow(GameService::notFound);
		var amount = game.shareDue(userId, Market.get(Market.DEFAULT));
		return new ShareDue(game.getId(), game.getTitle(), game.getHostId(), amount, game.getCurrency());
	}

	@Override
	@Transactional
	public SharePaid markPaidInApp(UUID gameId, UUID userId, UUID paymentId) {
		var game = games.lockById(gameId).orElseThrow(GameService::notFound);
		game.paidInApp(userId, paymentId);
		return new SharePaid(game.getId(), game.getTitle(), game.getHostId(), game.paidCount(), game.getParticipants().size());
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, String> titles(Collection<UUID> gameIds) {
		return games.findAllById(gameIds).stream().collect(Collectors.toMap(Game::getId, Game::getTitle));
	}

}
