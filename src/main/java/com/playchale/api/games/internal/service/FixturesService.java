package com.playchale.api.games.internal.service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.playchale.api.games.api.Fixtures;
import com.playchale.api.games.api.GameResponse;
import com.playchale.api.games.internal.domain.Game;
import com.playchale.api.games.internal.repository.GameRepository;
import com.playchale.api.games.internal.repository.GameResultRepository;
import com.playchale.api.market.Market;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Serves {@link Fixtures} to the competitions module. */
@Service
class FixturesService implements Fixtures {

	private final GameRepository games;

	private final GameResultRepository results;

	private final GameViews views;

	private final Clock clock;

	FixturesService(GameRepository games, GameResultRepository results, GameViews views, Clock clock) {
		this.games = games;
		this.results = results;
		this.views = views;
		this.clock = clock;
	}

	@Override
	@Transactional
	public void create(List<FixtureSpec> fixtures) {
		var now = clock.instant();
		var market = Market.get(Market.DEFAULT);
		for (var f : fixtures) {
			var game = Game.fixture(f.competitionId(), f.round(), f.homeTeamId(), f.awayTeamId(), f.title(), f.sport(), f.format(),
					f.startsAt(), f.durationMinutes(), f.organiserId(), f.squad(), market, now);
			if (Game.LISTED.equals(f.venueKind())) {
				game.playAt(f.venueId(), f.venueName(), f.venueArea(), null, null);
			}
			else {
				game.playAt(f.venueName(), f.venueArea());
			}
			games.save(game);
		}
	}

	@Override
	@Transactional
	public void syncSquad(UUID gameId, List<UUID> squad) {
		games.lockById(gameId).ifPresent(game -> game.syncSquad(squad, clock.instant()));
	}

	@Override
	@Transactional(readOnly = true)
	public List<FixtureSummary> of(UUID competitionId) {
		var fixtures = games.findByCompetitionIdOrderByStartsAt(competitionId);
		var scores = results.findAllById(fixtures.stream().map(Game::getId).toList()).stream()
			.collect(Collectors.toMap(r -> r.getGameId(), r -> r));
		return fixtures.stream().map(g -> {
			var r = scores.get(g.getId());
			return new FixtureSummary(g.getId(), g.getFixtureRound(), g.getHomeTeamId(), g.getAwayTeamId(), g.getStartsAt(), g.getStatus(),
					r == null ? null : r.getHomeScore(), r == null ? null : r.getAwayScore());
		}).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public List<GameResponse> views(UUID competitionId, UUID viewer) {
		return views.of(games.findByCompetitionIdOrderByStartsAt(competitionId), viewer);
	}

}
