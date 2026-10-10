package com.playchale.api.games.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.playchale.api.shared.maps.MapPin;
import com.playchale.api.users.api.UserSummary;

/**
 * A game as the web app's GameView type: the game, with the people it mentions filled in, each
 * player's share and the spots left (no {@code capacity} or {@code spotsLeft} for a game open to any
 * number, whose {@code totalCost} is then each player's price). {@code hostPayoutPhone} is where to send the host your share,
 * shown only to players in the game. {@code country} and {@code timezone} are where it's played: its
 * money is in that country's currency, and kick-off is in that local time. {@code distanceKm} is how
 * far it is from where the viewer asked, when Discover is sorted nearest first.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GameResponse(UUID id, String sport, String format, String title, Instant startsAt, int durationMinutes,
		VenueRef venue, Integer capacity, long totalCost, String pricing, String currency, String visibility, UUID hostId, String notes,
		List<ParticipantResponse> participants, String status, ResultResponse result, FixtureRef fixture, Instant createdAt,
		Instant cancelledAt, String cancelReason, UserSummary host, List<PlayerResponse> players, FixtureTeamsResponse fixtureTeams,
		long share, Integer spotsLeft, String hostPayoutPhone, List<InviteResponse> invites, FriendlyResponse friendly, String country,
		String timezone, SeriesRef series, Double distanceKm) {

	/** The same game, this far from the viewer. */
	public GameResponse withDistance(double km) {
		return new GameResponse(id, sport, format, title, startsAt, durationMinutes, venue, capacity, totalCost, pricing, currency, visibility,
				hostId, notes, participants, status, result, fixture, createdAt, cancelledAt, cancelReason, host, players, fixtureTeams, share,
				spotsLeft, hostPayoutPhone, invites, friendly, country, timezone, series, Math.round(km * 10) / 10.0);
	}

	/**
	 * The repeating game this is one of. {@code weekday} is ISO (1 is Monday), {@code kickOff} is
	 * "18:00" in the game's own time, and {@code weekOfMonth} (1 to 4, or -1 for the last) is set for a
	 * monthly one. {@code nextStartsAt} is the next game it will open and {@code opensAt} when.
	 * {@code optedOut} is whether the viewer asked not to be invited; null with nobody signed in.
	 */
	public record SeriesRef(UUID id, String frequency, int weekday, Integer weekOfMonth, String kickOff, String status, Instant nextStartsAt,
			Instant opensAt, Boolean optedOut) {
	}

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
	 * with a {@code mapUrl} for directions and a {@code pin} on the map when there are (for a listed venue, the venue's own).
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record VenueRef(String kind, UUID venueId, String name, String area, UUID pitchId, String pitchName, String mapUrl, MapPin pin) {
	}

	/**
	 * A spot. {@code userId} is "" while it's held for a guest. A guest's {@code token} is the
	 * spot's public ID, never the secret in the claim link.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ParticipantResponse(String userId, Instant joinedAt, boolean paid, UUID paymentId, String paidVia,
			Instant remindedAt, Guest guest, Boolean attended, Instant attendedAt) {
	}

	/**
	 * A guest spot as the web app's Guest type. The number and email are only shown to the host.
	 * {@code selfJoined}: they took the spot themselves, without an account, rather than the host
	 * holding it for them.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Guest(String name, String phone, String token, UUID addedBy, String email, Boolean selfJoined) {
	}

	/**
	 * Someone holding a spot, as the web app's User type plus whether they've paid. A guest has an
	 * id of "guest:<token>" and the host's note as their name.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PlayerResponse(String id, String phone, String name, String handle, String avatar, String avatarSeed, String tint,
			String area, List<String> sports, Map<String, List<String>> roles, Instant createdAt, boolean onboarded, String payoutPhone,
			boolean paid, Boolean attended, Guest guest) {
	}

}
