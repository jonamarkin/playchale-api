package com.playchale.api.competitions.internal.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.playchale.api.games.api.FixtureTeams.TeamCard;
import com.playchale.api.games.api.GameResponse;
import com.playchale.api.users.api.UserSummary;

/**
 * A competition as the web app's CompetitionView: with its teams, table, fixtures and the requests
 * waiting on captains.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompetitionResponse(UUID id, String name, String sport, String format, UUID organiserId, GameResponse.VenueRef venue,
		Instant startsAt, int durationMinutes, String status, Points points, Instant createdAt, UserSummary organiser,
		List<TeamView> teams, List<TableRow> table, List<GameResponse> fixtures, int rounds, List<RequestView> requests) {

	public record Points(int win, int draw, int loss) {
	}

	/**
	 * A team with its people, as the web app's TeamView. {@code joinToken} is "" for anyone but the
	 * captain and the organiser.
	 */
	public record TeamView(UUID id, UUID competitionId, String name, UUID captainId, List<UUID> playerIds, String tint, String joinToken,
			Instant createdAt, List<UserSummary> players, UserSummary captain) {
	}

	/** @param scored goals, points or sets, depending on the sport */
	public record TableRow(TeamCard team, int played, int won, int drawn, int lost, int scored, int conceded, int difference, int points,
			List<String> form) {
	}

	public record RequestView(UUID id, UUID competitionId, UUID teamId, UUID userId, String status, Instant createdAt, UserSummary user) {
	}

}
