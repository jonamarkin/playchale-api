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
 * waiting on captains. {@code organiser} set it up; {@code organisers} are the others running it
 * with them, who can do everything but change that list.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompetitionResponse(UUID id, String name, String sport, String format, UUID organiserId, GameResponse.VenueRef venue,
		Instant startsAt, int durationMinutes, String status, Points points, Instant createdAt, UserSummary organiser,
		List<TeamView> teams, List<TableRow> table, List<GameResponse> fixtures, int rounds, List<RequestView> requests, String playerLists,
		String country, String currency, String timezone, List<ScorerView> scorers, List<UserSummary> organisers, String structure,
		List<BracketRound> bracket, UUID organisationId, String scheduleStatus, List<String> permissions, Brand brand) {

	public record Brand(UUID organisationId, String name, String primaryColour, String logoUrl) { }

	public record Points(int win, int draw, int loss) {
	}

	/**
	 * A team in the league with its squad, as the web app's TeamView. {@code joinToken} is "" for
	 * anyone but the captain and the organiser; {@code status} is "invited" while its captain hasn't
	 * accepted (left out once it's in).
	 */
	public record TeamView(UUID id, UUID competitionId, String name, UUID captainId, List<UUID> playerIds, String tint, String joinToken,
			Instant createdAt, List<UserSummary> players, UserSummary captain, String status) {
	}

	/** @param scored goals, points or sets, depending on the sport */
	public record TableRow(TeamCard team, int played, int won, int drawn, int lost, int scored, int conceded, int difference, int points,
			List<String> form) {
	}

	/**
	 * A player on the scorers chart, best first. {@code team} is the squad they play for, or null in a
	 * league that keeps no player lists. Only the sport's own stats mean anything: goals and assists
	 * for football, points for basketball.
	 */
	public record ScorerView(UserSummary player, UUID teamId, String teamName, int goals, int assists, int points, int games) {
	}

	/**
	 * One round of a knockout bracket, for showing it whole: the ties played or still to come, and
	 * how many there are in the round even before they're drawn.
	 */
	public record BracketRound(int round, String name, int ties, List<BracketTie> played) {
	}

	/**
	 * A tie in the bracket. A bye is a tie with nobody to play: it has a team and a winner, but no
	 * game, because none is played.
	 */
	public record BracketTie(UUID gameId, int slot, TeamCard home, TeamCard away, Integer homeScore, Integer awayScore,
			Integer homePenalties, Integer awayPenalties, UUID winnerId, Instant startsAt, String status, boolean bye) {
	}

	public record RequestView(UUID id, UUID competitionId, UUID teamId, UUID userId, String status, Instant createdAt, UserSummary user) {
	}

}
