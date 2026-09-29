package com.playchale.api.games.internal.service;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

import com.playchale.api.games.api.Fixtures;
import com.playchale.api.games.api.GameResponse;
import com.playchale.api.games.internal.domain.Game;
import com.playchale.api.games.internal.repository.GameRepository;
import com.playchale.api.games.internal.repository.GameResultRepository;
import com.playchale.api.market.Market;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Serves {@link Fixtures} to the competitions module. */
@Service
class FixturesService implements Fixtures {

	private final GameRepository games;

	private final GameResultRepository results;

	private final GameViews views;

	private final Clock clock;

	private final JdbcClient jdbc;

	/** What a scorers chart counts, per sport. Volleyball and tennis keep no player stats. */
	private static final Map<String, ToIntFunction<Scorer>> RANKED_BY = Map.of("football", Scorer::goals, "basketball", Scorer::points);

	FixturesService(GameRepository games, GameResultRepository results, GameViews views, Clock clock, JdbcClient jdbc) {
		this.games = games;
		this.results = results;
		this.views = views;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Override
	@Transactional
	public void create(List<FixtureSpec> fixtures) {
		var now = clock.instant();
		for (var f : fixtures) {
			var game = Game.fixture(f.competitionId(), f.round(), f.homeTeamId(), f.awayTeamId(), f.title(), f.sport(), f.format(),
					f.startsAt(), f.durationMinutes(), f.organiserId(), f.squad(), Market.get(f.country()), f.timezone(), now, f.slot(), f.decider());
			if (Game.LISTED.equals(f.venueKind())) {
				game.playAt(f.venueId(), f.venueName(), f.venueArea(), null, null);
			}
			else {
				game.playAt(f.venueName(), f.venueArea(), f.venueMapUrl());
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
					r == null ? null : r.getHomeScore(), r == null ? null : r.getAwayScore(), g.getFixtureSlot(),
					r == null ? null : r.getHomePenalties(), r == null ? null : r.getAwayPenalties());
		}).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UUID> competitionOf(UUID gameId) {
		return games.findById(gameId).map(Game::getCompetitionId);
	}

	@Override
	@Transactional(readOnly = true)
	public List<GameResponse> views(UUID competitionId, UUID viewer) {
		return views.of(games.findByCompetitionIdOrderByStartsAt(competitionId), viewer);
	}

	/**
	 * Every player's goals, assists and points across a league's played fixtures, in the order the
	 * sport ranks them. Straight SQL, like a player's own record: a read that's all about the data.
	 * A player's team comes from the squad they're in, so a league without player lists has none.
	 */
	@Override
	@Transactional(readOnly = true)
	public List<Scorer> scorers(UUID competitionId, String sport) {
		var by = RANKED_BY.get(sport);
		if (by == null) {
			return List.of();
		}
		var scorers = jdbc.sql("""
				SELECT rp.user_id, ep.team_id, sum(rp.goals) AS goals, sum(rp.assists) AS assists,
				       sum(rp.points) AS points, count(*) AS games
				FROM result_players rp
				JOIN games g ON g.id = rp.game_id
				LEFT JOIN entry_players ep ON ep.competition_id = g.competition_id AND ep.user_id = rp.user_id
				WHERE g.competition_id = :competition AND g.status <> 'cancelled'
				  AND rp.user_id IS NOT NULL AND rp.side IN ('home', 'away')
				GROUP BY rp.user_id, ep.team_id
				""").param("competition", competitionId).query(Scorer.class).list();
		return scorers.stream().filter(s -> by.applyAsInt(s) > 0)
			.sorted(Comparator.comparingInt(by).reversed().thenComparing(Comparator.comparingInt(Scorer::assists).reversed())
					.thenComparing(Comparator.comparingInt(Scorer::games)))
			.toList();
	}

}
