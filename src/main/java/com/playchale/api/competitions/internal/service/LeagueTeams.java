package com.playchale.api.competitions.internal.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.playchale.api.competitions.internal.domain.Competition;
import com.playchale.api.competitions.internal.domain.Entry;
import com.playchale.api.competitions.internal.repository.CompetitionRepository;
import com.playchale.api.competitions.internal.repository.EntryRepository;
import com.playchale.api.games.api.FixtureTeams;
import com.playchale.api.teams.api.TeamLeagues;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tells other modules about teams in leagues: games shows fixtures' teams with their squads, and a
 * team's page lists the leagues it's in (which also keeps a team from being deleted mid-league).
 */
@Component
class LeagueTeams implements FixtureTeams, TeamLeagues {

	private final EntryRepository entries;

	private final CompetitionRepository competitions;

	private final CompetitionViews views;

	LeagueTeams(EntryRepository entries, CompetitionRepository competitions, CompetitionViews views) {
		this.entries = entries;
		this.competitions = competitions;
		this.views = views;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, TeamCard> teams(UUID competitionId, Collection<UUID> teamIds) {
		return views.cards(competitionId, teamIds);
	}

	@Override
	@Transactional(readOnly = true)
	public List<TeamLeague> leaguesOf(UUID teamId) {
		var mine = entries.ofTeam(teamId);
		var leagues = competitions.findAllById(mine.stream().map(Entry::getCompetitionId).toList()).stream()
			.collect(Collectors.toMap(Competition::getId, c -> c));
		return mine.stream().filter(e -> leagues.containsKey(e.getCompetitionId())).map(e -> {
			var c = leagues.get(e.getCompetitionId());
			return new TeamLeague(c.getId(), c.getName(), c.getStatus(), e.getStatus());
		}).toList();
	}

}
