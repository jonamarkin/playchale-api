package com.playchale.api.competitions.internal.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.playchale.api.competitions.api.TeamMemberships;
import com.playchale.api.competitions.internal.domain.Team;
import com.playchale.api.competitions.internal.repository.TeamRepository;
import com.playchale.api.games.api.FixtureTeams;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Tells other modules about teams: games shows them with fixtures, profiles lists who a player plays for. */
@Component
class TeamLookups implements FixtureTeams, TeamMemberships {

	private final TeamRepository teams;

	TeamLookups(TeamRepository teams) {
		this.teams = teams;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, TeamCard> teams(Collection<UUID> teamIds) {
		return CompetitionViews.cards(teams.findAllById(teamIds));
	}

	@Override
	@Transactional(readOnly = true)
	public List<String> teamNames(UUID userId) {
		return teams.playedForBy(userId).stream().map(Team::getName).distinct().toList();
	}

}
