package com.playchale.api.competitions.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Things that happen in competitions, for notifications. Published inside the change's transaction. */
public final class CompetitionEvents {

	private CompetitionEvents() {
	}

	/**
	 * @param noun what to call it to people: "league" or "tournament"
	 * @param country and @param timezone where it's played: kick-offs are said in that local time
	 */
	public record CompetitionInfo(UUID competitionId, String name, String noun, String country, String timezone) {
	}

	/** A captain or the organiser put players in a squad. */
	public record AddedToSquad(CompetitionInfo competition, String teamName, UUID by, List<UUID> playerIds) {
	}

	/** Someone asked a captain for a place. */
	public record SquadRequested(CompetitionInfo competition, String teamName, UUID captainId, UUID playerId) {
	}

	/** A captain (or the organiser) answered a request. */
	public record SquadAnswered(CompetitionInfo competition, String teamName, UUID by, UUID playerId, boolean accepted) {
	}

	/** Someone joined a squad from the link its captain shared. */
	public record JoinedByLink(CompetitionInfo competition, String teamName, UUID captainId, UUID playerId, int squadSize) {
	}

	/** The organiser invited a team they don't captain; its captain accepts or declines. */
	public record TeamInvited(CompetitionInfo competition, String teamName, UUID captainId, UUID organiserId) {
	}

	/** An invited team's captain answered. */
	public record EntryAnswered(CompetitionInfo competition, String teamName, UUID organiserId, UUID captainId, boolean accepted) {
	}

	/** The fixtures are out. */
	public record FixturesDrawn(CompetitionInfo competition, UUID organiserId, List<UUID> playerIds, int rounds, Instant startsAt) {
	}

}
