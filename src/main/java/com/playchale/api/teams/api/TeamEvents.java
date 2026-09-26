package com.playchale.api.teams.api;

import java.util.UUID;

/** Things that happen in teams. Published inside the change's transaction. */
public final class TeamEvents {

	private TeamEvents() {
	}

	/**
	 * Someone is in a team now: by its link, by a request the captain accepted, or added by the
	 * captain. Leagues put them in the team's squads where they're free.
	 *
	 * @param announce whether the teams module tells people (leagues tell their own story)
	 */
	public record MemberJoined(UUID teamId, String teamName, UUID captainId, UUID userId, UUID addedBy, boolean announce) {
	}

	/** Someone asked the captain for a place. */
	public record JoinRequested(UUID teamId, String teamName, UUID captainId, UUID userId, boolean announce) {
	}

	/** The captain answered a request. */
	public record RequestAnswered(UUID teamId, String teamName, UUID captainId, UUID userId, boolean accepted, boolean announce) {
	}

}
