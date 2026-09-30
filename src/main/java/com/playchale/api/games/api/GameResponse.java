package com.playchale.api.games.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.playchale.api.users.api.UserSummary;

/**
 * A game as the web app's GameView type: the game, with the people it mentions filled in, each
 * player's share and the spots left. {@code hostPayoutPhone} is where to send the host your share,
 * shown only to players in the game. {@code country} and {@code timezone} are where it's played: its
 * money is in that country's currency, and kick-off is in that local time.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GameResponse(UUID id, String sport, String format, String title, Instant startsAt, int durationMinutes,
		VenueRef venue, int capacity, long totalCost, String pricing, String currency, String visibility, UUID hostId, String notes,
		List<ParticipantResponse> participants, String status, ResultResponse result, FixtureRef fixture, Instant createdAt,
		Instant cancelledAt, String cancelReason, UserSummary host, List<PlayerResponse> players, FixtureTeamsResponse fixtureTeams,
		long share, int spotsLeft, String hostPayoutPhone, List<InviteResponse> invites, FriendlyResponse friendly, String country,
		String timezone) {

	/**
	 * A friendly's two teams, each with who's playing for it here, and where the challenge stands:
	 * "pending" (the away captain hasn't answered), "accepted" or "declined".
	 */
	public record FriendlyResponse(TeamSide home, TeamSide away, String opponentStatus) {
	}

	/** One side of a friendly. {@code playerIds} are the roster keys of those playing for it. */
	public record TeamSide(UUID id, String name, String tint, UUID captainId, List<String> playerIds) {
	}

	/**
	 * An invite and its answer: status "pending", "accepted" or "declined". The host sees everyone's;
	 * an invited player sees only their own; everyone else sees none.
	 *
	 * @param teamName set when they were invited with their team
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record InviteResponse(UUID userId, UUID teamId, String teamName, UUID invitedBy, String status, Instant invitedAt,
			Instant answeredAt, UserSummary user) {
	}

	/**
	 * What makes a game a competition fixture: which competition, which round, who's playing.
	 * {@code slot} and {@code decider} place a knockout tie in its bracket and say it must be won.
	 * {@code organiser} is whether the viewer runs that competition, and so may record its result or
	 * call it off even though someone else made the draw and hosts it.
	 */
	public record FixtureRef(UUID competitionId, int round, UUID homeTeamId, UUID awayTeamId, Integer slot, boolean decider,
			boolean organiser) {
	}

	/** The two teams in a fixture. */
	public record FixtureTeamsResponse(FixtureTeams.TeamCard home, FixtureTeams.TeamCard away) {
	}

	/** The web app's GameResult. Players are named by the roster's keys: a player's ID, or "guest:<token>". */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ResultResponse(int homeScore, int awayScore, Sides sides, List<Scorer> scorers, List<SetScore> sets,
			List<String> absent, UUID verifiedBy, Instant recordedAt, List<UUID> confirmedBy, List<Dispute> disputes,
			Integer homePenalties, Integer awayPenalties) {
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

	/**
	 * Where it's played: {kind: "listed", venueId, name, area, pitchId?, pitchName?} or {kind: "unlisted", name, area?}, each
	 * with a {@code mapUrl} for directions when there is one (for a listed venue, the venue's own).
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record VenueRef(String kind, UUID venueId, String name, String area, UUID pitchId, String pitchName, String mapUrl) {
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
	public record PlayerResponse(String id, String phone, String name, String handle, String avatar, String avatarSeed, String tint,
			String area, List<String> sports, Map<String, List<String>> roles, Instant createdAt, boolean onboarded, String payoutPhone,
			boolean paid, Guest guest) {
	}

}
