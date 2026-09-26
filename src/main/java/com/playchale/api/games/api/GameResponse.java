package com.playchale.api.games.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.playchale.api.users.api.UserSummary;

/**
 * A game as the web app's GameView type: the game, with the people it mentions filled in, each
 * player's share and the spots left. {@code hostPayoutPhone} is where to send the host your share,
 * shown only to players in the game.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GameResponse(UUID id, String sport, String format, String title, Instant startsAt, int durationMinutes,
		VenueRef venue, int capacity, long totalCost, String currency, String visibility, UUID hostId, String notes,
		List<ParticipantResponse> participants, String status, ResultResponse result, FixtureRef fixture, Instant createdAt,
		Instant cancelledAt, String cancelReason, UserSummary host, List<PlayerResponse> players, FixtureTeamsResponse fixtureTeams,
		long share, int spotsLeft, String hostPayoutPhone) {

	/** What makes a game a league fixture: which league, which round, who's playing. */
	public record FixtureRef(UUID competitionId, int round, UUID homeTeamId, UUID awayTeamId) {
	}

	/** The two teams in a fixture. */
	public record FixtureTeamsResponse(FixtureTeams.TeamCard home, FixtureTeams.TeamCard away) {
	}

	/** The web app's GameResult. Players are named by the roster's keys: a player's ID, or "guest:<token>". */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ResultResponse(int homeScore, int awayScore, Sides sides, List<Scorer> scorers, List<SetScore> sets,
			List<String> absent, UUID verifiedBy, Instant recordedAt, List<UUID> confirmedBy, List<Dispute> disputes) {
	}

	public record Sides(List<String> home, List<String> away) {
	}

	/** Only the sport's own stats are set: goals and assists for football, points for basketball. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Scorer(String userId, Integer goals, Integer assists, Integer points) {
	}

	public record SetScore(int home, int away) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Dispute(UUID userId, String reason, Instant at) {
	}

	/** Where it's played: {kind: "listed", venueId, name, area, pitchId?, pitchName?} or {kind: "unlisted", name, area?}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record VenueRef(String kind, UUID venueId, String name, String area, UUID pitchId, String pitchName) {
	}

	/**
	 * A spot. {@code userId} is "" while it's held for a guest. A guest's {@code token} is the
	 * spot's public ID, never the secret in the claim link.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ParticipantResponse(String userId, Instant joinedAt, boolean paid, UUID paymentId, String paidVia,
			Instant remindedAt, Guest guest) {
	}

	/** A guest spot as the web app's Guest type. The number is only shown to the host. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Guest(String name, String phone, String token, UUID addedBy) {
	}

	/**
	 * Someone holding a spot, as the web app's User type plus whether they've paid. A guest has an
	 * id of "guest:<token>" and the host's note as their name.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PlayerResponse(String id, String phone, String name, String handle, String avatar, String tint,
			String area, List<String> sports, String position, Instant createdAt, boolean onboarded, String payoutPhone,
			boolean paid, Guest guest) {
	}

}
