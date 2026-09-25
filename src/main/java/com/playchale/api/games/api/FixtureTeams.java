package com.playchale.api.games.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The two teams in a fixture, shown with it. Declared here and implemented by the competitions
 * module, so games can show them without depending on competitions (which depends on games).
 */
public interface FixtureTeams {

	/** A team as the web app's Team type. Its squad link is never shown with a fixture. */
	record TeamCard(UUID id, UUID competitionId, String name, UUID captainId, List<UUID> playerIds, String tint, String joinToken,
			Instant createdAt) {
	}

	Map<UUID, TeamCard> teams(Collection<UUID> teamIds);

}
