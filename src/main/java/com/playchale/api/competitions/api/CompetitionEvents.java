package com.playchale.api.competitions.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Things that happen in competitions, for notifications. Published inside the change's transaction. */
public final class CompetitionEvents {

	private CompetitionEvents() {
	}

	public record LeagueInfo(UUID competitionId, String name) {
	}

	/** A captain or the organiser put players in a squad. */
	public record AddedToSquad(LeagueInfo league, String teamName, UUID by, List<UUID> playerIds) {
	}

	/** Someone asked a captain for a place. */
	public record SquadRequested(LeagueInfo league, String teamName, UUID captainId, UUID playerId) {
	}

	/** A captain (or the organiser) answered a request. */
	public record SquadAnswered(LeagueInfo league, String teamName, UUID by, UUID playerId, boolean accepted) {
	}

	/** Someone joined a squad from the link its captain shared. */
	public record JoinedByLink(LeagueInfo league, String teamName, UUID captainId, UUID playerId, int squadSize) {
	}

	/** The fixtures are out. */
	public record FixturesDrawn(LeagueInfo league, UUID organiserId, List<UUID> playerIds, int rounds, Instant startsAt) {
	}

}
