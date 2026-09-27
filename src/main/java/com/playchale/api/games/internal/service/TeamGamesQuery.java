package com.playchale.api.games.internal.service;

import java.time.Clock;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.playchale.api.games.internal.domain.Game;
import com.playchale.api.games.internal.domain.GameResult;
import com.playchale.api.games.internal.repository.GameRepository;
import com.playchale.api.games.internal.repository.GameResultRepository;
import com.playchale.api.teams.api.TeamCard;
import com.playchale.api.teams.api.TeamDirectory;
import com.playchale.api.teams.api.TeamGames;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** A team's record and games for its page, from league fixtures and friendlies alike. */
@Component
class TeamGamesQuery implements TeamGames {

	/** Enough for any team's record; a page shows a handful. */
	private static final int HISTORY = 500;

	private static final int SHOWN = 5;

	private final GameRepository games;

	private final GameResultRepository results;

	private final TeamDirectory teams;

	private final Clock clock;

	TeamGamesQuery(GameRepository games, GameResultRepository results, TeamDirectory teams, Clock clock) {
		this.games = games;
		this.results = results;
		this.teams = teams;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public Summary of(UUID teamId) {
		var now = clock.instant();
		var all = games.ofTeam(teamId, Limit.of(HISTORY));
		var played = all.stream().filter(g -> Game.COMPLETED.equals(g.getStatus())).toList();
		var scores = played.isEmpty() ? Map.<UUID, GameResult>of()
				: results.findAllById(played.stream().map(Game::getId).toList()).stream().collect(Collectors.toMap(GameResult::getGameId, r -> r));
		var opponents = teams.findAll(all.stream().map(g -> opponent(g, teamId)).distinct().toList());

		var recent = played.stream().filter(g -> scores.containsKey(g.getId())).map(g -> view(g, teamId, opponents, scores.get(g.getId()))).toList();
		int won = 0;
		int drawn = 0;
		for (var g : recent) {
			won += "W".equals(g.outcome()) ? 1 : 0;
			drawn += "D".equals(g.outcome()) ? 1 : 0;
		}
		var upcoming = all.stream()
			.filter(g -> !Game.COMPLETED.equals(g.getStatus()) && !g.hasStarted(now))
			.sorted(Comparator.comparing(Game::getStartsAt))
			.limit(SHOWN)
			.map(g -> view(g, teamId, opponents, null))
			.toList();
		return new Summary(new Record(recent.size(), won, drawn, recent.size() - won - drawn), upcoming,
				recent.stream().limit(SHOWN).toList());
	}

	private static UUID opponent(Game g, UUID teamId) {
		return teamId.equals(g.getHomeTeamId()) ? g.getAwayTeamId() : g.getHomeTeamId();
	}

	private static TeamGame view(Game g, UUID teamId, Map<UUID, TeamCard> opponents, GameResult result) {
		var home = teamId.equals(g.getHomeTeamId());
		var opponent = opponents.get(opponent(g, teamId));
		Integer scoreFor = null;
		Integer scoreAgainst = null;
		String outcome = null;
		if (result != null) {
			scoreFor = home ? result.getHomeScore() : result.getAwayScore();
			scoreAgainst = home ? result.getAwayScore() : result.getHomeScore();
			outcome = scoreFor > scoreAgainst ? "W" : scoreFor < scoreAgainst ? "L" : "D";
		}
		return new TeamGame(g.getId(), g.getTitle(), g.getStartsAt(), opponent(g, teamId), opponent == null ? "A team no longer on PlayChale"
				: opponent.name(), home, g.getCompetitionId(), g.getOpponentStatus(), scoreFor, scoreAgainst, outcome);
	}

}
