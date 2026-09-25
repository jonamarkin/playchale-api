package com.playchale.api.games.internal.service;

import com.playchale.api.games.api.GameResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.UUID;

import com.playchale.api.games.api.GameEvents;
import com.playchale.api.games.internal.domain.Game;
import com.playchale.api.games.internal.domain.GameResult;
import com.playchale.api.games.internal.domain.ResultInput;
import com.playchale.api.games.internal.domain.ResultLine;
import com.playchale.api.games.internal.repository.GameRepository;
import com.playchale.api.games.internal.repository.GameResultRepository;
import com.playchale.api.shared.error.BusinessException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Results: the host records the score, and the players check it. Recording completes the game, and
 * everyone who played gets their outcome.
 */
@Service
public class ResultService {

	private final GameRepository games;

	private final GameResultRepository results;

	private final GameViews views;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	ResultService(GameRepository games, GameResultRepository results, GameViews views, ApplicationEventPublisher events,
			Clock clock) {
		this.games = games;
		this.results = results;
		this.views = views;
		this.events = events;
		this.clock = clock;
	}

	/** games.recordResult: host only, once the game has kicked off. Recording again corrects it. */
	@Transactional
	public GameResponse record(UUID gameId, ResultInput input, UUID me) {
		var game = games.lockById(gameId).orElseThrow(GameService::notFound);
		if (!game.isHost(me)) {
			throw BusinessException.conflict("Only the host can record the result.");
		}
		var now = clock.instant();
		game.complete(now);
		var existing = results.findById(gameId);
		var result = existing.orElseGet(() -> new GameResult(game, input, me, now));
		if (existing.isPresent()) {
			result.record(game, input, me, now);
		}
		results.save(result);

		var outcomes = new ArrayList<GameEvents.PlayerOutcome>();
		for (var spot : game.getParticipants()) {
			var player = spot.getUserId();
			if (player == null || game.isHost(player) || result.lineOf(player).map(l -> ResultLine.ABSENT.equals(l.side())).orElse(false)) {
				continue;
			}
			var home = result.lineOf(player).map(l -> ResultLine.HOME.equals(l.side())).orElse(true);
			outcomes.add(new GameEvents.PlayerOutcome(player, result.outcomeFor(player).orElse(null),
					home ? result.getHomeScore() : result.getAwayScore(), home ? result.getAwayScore() : result.getHomeScore()));
		}
		events.publishEvent(new GameEvents.ResultRecorded(GameService.info(game), existing.isPresent(), !result.getSets().isEmpty(),
				result.getHomeScore(), result.getAwayScore(), outcomes));
		return views.of(game, me);
	}

	/** games.confirmResult */
	@Transactional
	public GameResponse confirm(UUID gameId, UUID me) {
		var game = games.findById(gameId).orElseThrow(GameService::notFound);
		resultOf(game).confirm(me, clock.instant());
		return views.of(game, me);
	}

	/** games.disputeResult: the host hears about it and can correct the result. */
	@Transactional
	public GameResponse dispute(UUID gameId, String reason, UUID me) {
		var game = games.findById(gameId).orElseThrow(GameService::notFound);
		var kept = resultOf(game).dispute(me, reason, clock.instant());
		events.publishEvent(new GameEvents.ResultDisputed(GameService.info(game), me, kept));
		return views.of(game, me);
	}

	private GameResult resultOf(Game game) {
		return results.findById(game.getId()).orElseThrow(() -> BusinessException.conflict("There’s no result to check yet."));
	}

}
