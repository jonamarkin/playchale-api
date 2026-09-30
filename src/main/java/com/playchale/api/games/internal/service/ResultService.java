package com.playchale.api.games.internal.service;

import com.playchale.api.games.api.GameResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.playchale.api.games.api.GameEvents;
import com.playchale.api.games.api.OfficialResults;
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
 * Results: whoever runs the game records the score, and the players check it. Recording completes
 * the game, and everyone who played gets their outcome.
 */
@Service
public class ResultService implements OfficialResults {

	private final GameRepository games;

	private final GameResultRepository results;

	private final GameViews views;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	private final FixtureRunners runners;

	ResultService(GameRepository games, GameResultRepository results, GameViews views, ApplicationEventPublisher events,
			Clock clock, FixtureRunners runners) {
		this.games = games;
		this.results = results;
		this.views = views;
		this.events = events;
		this.clock = clock;
		this.runners = runners;
	}

	/**
	 * games.recordResult: whoever runs it — the host, or any organiser of the competition it's a
	 * fixture of — once the game has kicked off. Recording again corrects it.
	 */
	@Transactional
	public GameResponse record(UUID gameId, ResultInput input, UUID me) {
		var game = games.lockById(gameId).orElseThrow(GameService::notFound);
		if (!runners.runs(game, me)) {
			throw BusinessException.conflict(FixtureRunners.refusal(game, "Only the host can record the result.", "record the result"));
		}
		var now = clock.instant();
		// A called-off fixture can still be settled, so a knockout that lost a tie can reach its final.
		game.reinstate();
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

	@Override
	@Transactional
	public void record(UUID gameId, int homeScore, int awayScore, List<OfficialResults.Player> players, UUID actorId) {
		var game = games.lockById(gameId).orElseThrow(GameService::notFound);
		var now = clock.instant();
		game.reinstate();
		game.complete(now);
		var home = players.stream().filter(p -> "home".equals(p.side())).map(p -> p.userId().toString()).toList();
		var away = players.stream().filter(p -> "away".equals(p.side())).map(p -> p.userId().toString()).toList();
		var absent = players.stream().filter(p -> "absent".equals(p.side())).map(p -> p.userId().toString()).toList();
		var scorers = players.stream().filter(p -> p.goals() > 0 || p.assists() > 0)
			.map(p -> new ResultInput.Scorer(p.userId().toString(), p.goals(), p.assists(), 0)).toList();
		var input = new ResultInput(homeScore, awayScore, home, away, scorers, List.of(), absent);
		var existing = results.findById(gameId);
		var result = existing.orElseGet(() -> new GameResult(game, input, actorId, now));
		if (existing.isPresent()) {
			result.record(game, input, actorId, now);
		}
		results.save(result);
		events.publishEvent(new GameEvents.ResultRecorded(GameService.info(game), existing.isPresent(), false,
			result.getHomeScore(), result.getAwayScore(), List.of()));
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
