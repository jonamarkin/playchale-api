package com.playchale.api.games.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Things that happen in games, published after the change and inside the same transaction, so
 * listeners' work (a notification, a ledger line) is saved together with it or not at all. Each
 * carries what a listener needs, so none has to call back into the games module.
 */
public final class GameEvents {

	private GameEvents() {
	}

	/**
	 * The facts about a game most events need.
	 *
	 * @param country  where it's played (ISO 3166-1): its money is written that country's way
	 * @param timezone where it's played (IANA): kick-off is said in this local time
	 */
	public record GameInfo(UUID gameId, String title, Instant startsAt, UUID hostId, String venueName, String country, String timezone) {
	}

	/**
	 * The venue moved a game's booking: to another pitch, another time, or both.
	 *
	 * @param from        the kick-off before the move ({@code game.startsAt()} is the new one)
	 * @param fromPitch   the pitch before the move
	 * @param playerIds   everyone in the game, host included
	 */
	public record GameMoved(GameInfo game, Instant from, String fromPitch, String pitchName, List<UUID> playerIds) {
	}

	/** A game was created on a partner venue's pitch. */
	public record PitchBooked(GameInfo game, UUID venueId, UUID venueOwnerId, String pitchName) {
	}

	/**
	 * Someone took a spot: by joining, or by claiming the one the host held for them.
	 *
	 * @param filled spots taken now, out of {@code capacity}
	 */
	public record PlayerJoined(GameInfo game, UUID playerId, int filled, int capacity, boolean claimedGuestSpot) {
	}

	/** The host took a player off the roster. */
	public record PlayerRemoved(GameInfo game, UUID playerId) {
	}

	/**
	 * The host called the game off.
	 *
	 * @param venueOwnerId set when a partner venue's pitch was released
	 */
	public record GameCalledOff(GameInfo game, List<UUID> playerIds, String reason, UUID venueId, UUID venueOwnerId,
			String pitchName) {
	}

	/**
	 * The host invited players: people they've played with, or a team they're in. Each accepts or
	 * declines; nobody is put in the game without saying yes.
	 *
	 * @param invitedBy the host, or in a friendly the away team's captain asking their own players
	 * @param teamName  set when a team was invited
	 */
	public record PlayersInvited(GameInfo game, UUID invitedBy, List<UUID> playerIds, long share, String currency, String teamName) {
	}

	/** A team's captain challenged another team to a friendly; that team's captain accepts or declines. */
	public record ChallengeSent(GameInfo game, String homeTeam, String awayTeam, UUID awayCaptainId) {
	}

	/** The challenged team's captain answered. */
	public record ChallengeAnswered(GameInfo game, String homeTeam, String awayTeam, UUID captainId, boolean accepted) {
	}

	/** An invited player said they can't make it. */
	public record InviteDeclined(GameInfo game, UUID playerId) {
	}

	/**
	 * The host nudged players who haven't paid.
	 *
	 * @param perPlayer true when {@code share} is the price to take part, not a share of a total
	 */
	public record PaymentReminded(GameInfo game, List<UUID> playerIds, long share, String currency, boolean perPlayer) {
	}

	/**
	 * The host recorded a share paid to them in cash.
	 *
	 * @param payerId null when it was a guest, who has no account
	 */
	public record CashShareCollected(GameInfo game, UUID payerId, long amount, String currency) {
	}

	/**
	 * The host recorded (or corrected) a result.
	 *
	 * @param players each player on a side, with their outcome and score from their side
	 */
	public record ResultRecorded(GameInfo game, boolean corrected, boolean inSets, int homeScore, int awayScore,
			List<PlayerOutcome> players) {
	}

	/** @param outcome "W", "D" or "L"; null for someone in the game who wasn't on a side */
	public record PlayerOutcome(UUID playerId, String outcome, int scoreFor, int scoreAgainst) {
	}

	/** A player says the result isn't right. */
	public record ResultDisputed(GameInfo game, UUID playerId, String reason) {
	}

}
