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

	/**
	 * The host changed a game still to come in a way its players need to hear about.
	 *
	 * @param time          kick-off or length changed ({@code game.startsAt()} is the new kick-off)
	 * @param place         it's somewhere else now ({@code game.venueName()})
	 * @param money         what each player pays changed, to {@code share} (0: free)
	 * @param playerIds     everyone in it with an account, but the host
	 * @param venueOwnerId  the partner venue's owner when its booked pitch moved to the new time, else null
	 */
	public record GameChanged(GameInfo game, boolean time, boolean place, boolean money, long share, List<UUID> playerIds, UUID venueId,
			UUID venueOwnerId, String pitchName) {
	}

	/** A game was created on a partner venue's pitch. */
	public record PitchBooked(GameInfo game, UUID venueId, UUID venueOwnerId, String pitchName) {
	}

	/**
	 * Someone in a game said something to the others in it.
	 *
	 * @param said      who said it
	 * @param body      what they said, already trimmed and length-checked
	 * @param recipients everyone else in the game: the only people it is ever shown to
	 */
	public record MessagePosted(GameInfo game, UUID said, String body, List<UUID> recipients) {
	}

	/**
	 * Someone took a spot: by joining, or by claiming the one the host held for them.
	 *
	 * @param filled spots taken now, out of {@code capacity}
	 */
	public record PlayerJoined(GameInfo game, UUID playerId, int filled, int capacity, boolean claimedGuestSpot) {
	}

	/** Someone without an account took a spot themselves, as a guest. */
	public record GuestJoined(GameInfo game, String guestName, int filled, int capacity) {
	}

	/**
	 * Someone signed in with the number or address a guest spot was taken under, and the spot became
	 * theirs. {@code played} when the game was already over: its result now counts for them.
	 */
	public record GuestSpotClaimed(GameInfo game, UUID playerId, boolean played) {
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
	 * @param regulars  a repeating game's next game, inviting the last one's players: nobody asked them by hand
	 */
	public record PlayersInvited(GameInfo game, UUID invitedBy, List<UUID> playerIds, long share, String currency, String teamName,
			boolean regulars) {
	}

	/**
	 * A repeating game opened its next game and invited the last one's players.
	 *
	 * @param invited how many were invited (they're told by {@link PlayersInvited})
	 */
	public record SeriesGameOpened(GameInfo game, UUID seriesId, int invited) {
	}

	/**
	 * A repeating game couldn't open one of its dates (its pitch was booked, the venue is closed). It
	 * carries on with the date after.
	 */
	public record SeriesDateMissed(UUID seriesId, UUID hostId, String title, Instant startsAt, String country, String timezone, String reason) {
	}

	/** A repeating game paused itself rather than open another game nobody comes to. */
	public record SeriesPaused(UUID seriesId, UUID hostId, String title, String reason) {
	}

	/** The host started, changed, stopped or restarted a repeating game. {@code change} says which. */
	public record SeriesUpdated(UUID seriesId, UUID hostId, String change) {
	}

	/** A player asked not to be invited to a repeating game's games ({@code out}), or asked to be again. */
	public record SeriesOptOut(UUID seriesId, UUID userId, boolean out) {
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
