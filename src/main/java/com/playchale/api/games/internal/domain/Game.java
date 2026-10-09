package com.playchale.api.games.internal.domain;

import java.time.Duration;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.playchale.api.catalog.api.SportCatalog;
import com.playchale.api.market.Market;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.shared.maps.MapLink;
import com.playchale.api.shared.maps.Pin;
import com.playchale.api.shared.persistence.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * A game and everyone in it. The rules about who can join, leave, be removed or be held a spot
 * live here, so every way in (the app, a claim link, a repeat) obeys the same ones.
 */
@Entity
@Table(name = "games")
public class Game extends AuditableEntity {
	/** A total (pitch hire, balls, bibs) shared by the spots. */
	public static final String SPLIT = "split";

	/** What each player pays to take part: a contribution, not a share of anything. */
	public static final String PER_PLAYER = "per-player";


	public static final String LISTED = "listed";

	public static final String UNLISTED = "unlisted";

	public static final String OPEN = "open";

	public static final String FULL = "full";

	public static final String COMPLETED = "completed";

	public static final String CANCELLED = "cancelled";

	static final Set<String> VISIBILITIES = Set.of("public", "private");

	public static final String CHALLENGE_PENDING = "pending";

	public static final String CHALLENGE_ACCEPTED = "accepted";

	public static final String CHALLENGE_DECLINED = "declined";

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	private String sport;

	private String format;

	private String title;

	private Instant startsAt;

	private int durationMinutes;

	private String venueKind;

	private UUID venueId;

	private String venueName;

	private String venueArea;

	/**
	 * A map link for directions, for a game anywhere but a partner venue (see MapLink): from its
	 * {@link #pin} when it has one, else a link the host pasted. A partner venue's own link is looked
	 * up instead, so this stays null for those.
	 */
	private String mapUrl;

	/** Where a game anywhere but a partner venue is on the map. A partner venue's own is looked up instead. */
	@Embedded
	private Pin pin;

	private UUID pitchId;

	private String pitchName;

	private int capacity;

	private long totalCost;

	/** {@link #SPLIT} or {@link #PER_PLAYER}: how the cost is set, and so how the app describes it. */
	private String pricing = SPLIT;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 3)
	private String currency;

	/** Where it's played, ISO 3166-1: its money and phone numbers go by this country. */
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 2)
	private String country;

	/** Where it's played, IANA: kick-off is shown in this local time. */
	private String timezone;

	private String visibility;

	private UUID hostId;

	private String notes;

	private String status;

	private Instant cancelledAt;

	private String cancelReason;

	private UUID competitionId;

	private Integer fixtureRound;

	private UUID homeTeamId;

	private UUID awayTeamId;

	/** Which tie of its round a knockout fixture is; null for a league fixture. */
	private Integer fixtureSlot;

	/** A tie that has to produce a winner, so a level score is settled on penalties. */
	private boolean fixtureDecider;

	/**
	 * A friendly's challenge: {@link #CHALLENGE_PENDING} until the other team's captain answers. Null
	 * for any other game.
	 */
	private String opponentStatus;

	/** The repeating game this is one of ({@link GameSeries}), or null for a one-off. */
	private UUID seriesId;

	@OneToMany(mappedBy = "game", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("joinedAt")
	private List<Participant> participants = new ArrayList<>();

	/**
	 * Spots given up, kept after the spot itself is gone. Nothing about running a game reads this —
	 * {@link #participants} alone says who is in — so a departure can never hold a spot by accident.
	 */
	@OneToMany(mappedBy = "game", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("leftAt")
	private List<Departure> departures = new ArrayList<>();

	protected Game() {
	}

	/**
	 * A new game. The host takes the first spot and pays their share like everyone else (so in a
	 * free game they're already "paid").
	 */
	public Game(GameDetails details, UUID hostId, Market market, Instant now) {
		var sport = SportCatalog.find(details.sport());
		if (sport.isEmpty() || !sport.get().formats().contains(details.format())) {
			throw BusinessException.invalid("Pick a sport and format.");
		}
		if (details.capacity() < 2) {
			throw BusinessException.invalid("A game needs at least 2 spots.");
		}
		if (details.capacity() > 100) {
			throw BusinessException.invalid("A game can have at most 100 spots.");
		}
		if (details.startsAt() == null || !details.startsAt().isAfter(now)) {
			throw BusinessException.invalid("Pick a time in the future.");
		}
		if (details.durationMinutes() < 15 || details.durationMinutes() > 480) {
			throw BusinessException.invalid("Pick how long the game lasts.");
		}
		if (details.totalCost() < 0) {
			throw BusinessException.invalid("The cost can’t be less than nothing.");
		}
		if (details.visibility() == null || !VISIBILITIES.contains(details.visibility())) {
			throw BusinessException.invalid("Choose who can see the game.");
		}
		this.sport = details.sport();
		this.format = details.format();
		var title = details.title() == null ? "" : details.title().strip();
		if (title.length() > 80) {
			throw BusinessException.invalid("Keep the title under 80 characters.");
		}
		this.title = title.isEmpty() ? "%s %s".formatted(details.format(), sport.get().label().toLowerCase()) : title;
		this.startsAt = details.startsAt();
		this.durationMinutes = details.durationMinutes();
		this.capacity = details.capacity();
		this.totalCost = details.totalCost();
		var pricing = details.pricing() == null ? SPLIT : details.pricing();
		if (!SPLIT.equals(pricing) && !PER_PLAYER.equals(pricing)) {
			throw BusinessException.invalid("Choose how the cost works.");
		}
		// Per player, the total is always the price times the spots, so every spot pays exactly the price.
		if (PER_PLAYER.equals(pricing) && details.totalCost() % details.capacity() != 0) {
			throw BusinessException.invalid("Set what each player pays to take part.");
		}
		this.pricing = details.totalCost() == 0 ? SPLIT : pricing;
		this.currency = market.currency();
		this.country = market.country();
		this.timezone = market.timezone();
		this.visibility = details.visibility();
		this.hostId = hostId;
		var notes = details.notes() == null ? "" : details.notes().strip();
		if (notes.length() > 1000) {
			throw BusinessException.invalid("Keep the notes under 1,000 characters.");
		}
		this.notes = notes.isEmpty() ? null : notes;
		this.participants.add(Participant.player(this, hostId, totalCost == 0, now));
		syncStatus();
	}

	/**
	 * Checks {@code details} as creating a game from them would, without creating one: for a repeating
	 * game's settings, which have to be right before its next game is due rather than when it fails to open.
	 */
	public static void check(GameDetails details, UUID hostId, Market market, Instant now) {
		new Game(details, hostId, market, now);
	}

	/**
	 * A competition fixture. The organiser hosts it, the two squads fill it, and nobody pays through
	 * the app for it. {@code slot} and {@code decider} place a knockout tie in its bracket and say it
	 * has to produce a winner; a league fixture has neither.
	 */
	public static Game fixture(UUID competitionId, int round, UUID homeTeamId, UUID awayTeamId, String title, String sport,
			String format, Instant startsAt, int durationMinutes, UUID organiserId, List<UUID> squad, Market market, String timezone,
			Instant now, Integer slot, boolean decider) {
		var game = new Game();
		game.sport = sport;
		game.format = format;
		game.title = title;
		game.startsAt = startsAt;
		game.durationMinutes = durationMinutes;
		game.totalCost = 0;
		game.currency = market.currency();
		game.country = market.country();
		game.keepTime(timezone);
		game.visibility = "public";
		game.hostId = organiserId;
		game.competitionId = competitionId;
		game.fixtureRound = round;
		game.fixtureSlot = slot;
		game.fixtureDecider = decider;
		game.homeTeamId = homeTeamId;
		game.awayTeamId = awayTeamId;
		game.capacity = Math.max(2, squad.size());
		squad.stream().distinct().forEach(player -> game.participants.add(Participant.player(game, player, true, now)));
		game.syncStatus();
		return game;
	}

	/**
	 * Makes this a friendly between two teams: the host's team at home. The host plays for it. The
	 * away team is in once its captain accepts ({@code accepted} when the host captains both).
	 */
	public void challenge(UUID homeTeamId, UUID awayTeamId, boolean accepted) {
		this.homeTeamId = homeTeamId;
		this.awayTeamId = awayTeamId;
		this.opponentStatus = accepted ? CHALLENGE_ACCEPTED : CHALLENGE_PENDING;
		spotOf(hostId).ifPresent(p -> p.playFor(homeTeamId));
	}

	/** The away team's captain answered the challenge. */
	public void answerChallenge(boolean accept, Instant now) {
		if (accept) {
			requireOn("This game was called off.");
			requireNotPlayed(now);
		}
		this.opponentStatus = accept ? CHALLENGE_ACCEPTED : CHALLENGE_DECLINED;
	}

	/** One of a repeating game's games. */
	public void belongTo(UUID seriesId) {
		this.seriesId = seriesId;
	}

	public UUID getSeriesId() {
		return seriesId;
	}

	/** A game between two teams outside a league. */
	public boolean isFriendly() {
		return opponentStatus != null;
	}

	public boolean awaitsOpponent() {
		return CHALLENGE_PENDING.equals(opponentStatus);
	}

	/** Whether the away team is playing: the challenge was accepted. */
	public boolean opponentIn() {
		return CHALLENGE_ACCEPTED.equals(opponentStatus);
	}

	/** Brings an unplayed fixture's roster in line with its squads. Played, started or called-off fixtures stay as they were. */
	public void syncSquad(List<UUID> squad, Instant now) {
		if (competitionId == null || COMPLETED.equals(status) || CANCELLED.equals(status) || hasStarted(now)) {
			return;
		}
		participants.removeIf(p -> p.isGuest() || !squad.contains(p.getUserId()));
		for (var player : squad.stream().distinct().toList()) {
			if (spotOf(player).isEmpty()) {
				participants.add(Participant.player(this, player, true, now));
			}
		}
		capacity = Math.max(2, participants.size());
		syncStatus();
	}

	/** At a partner venue, maybe on a pitch booked for it. */
	public void playAt(UUID venueId, String venueName, String venueArea, UUID pitchId, String pitchName) {
		this.venueKind = LISTED;
		this.venueId = venueId;
		this.venueName = venueName;
		this.venueArea = venueArea;
		this.pitchId = pitchId;
		this.pitchName = pitchName;
		this.mapUrl = null;
		this.pin = null;
	}

	/**
	 * The venue moved this game's booking: another pitch, another time, or both (same length). Only
	 * for a game that's still to come.
	 */
	public void movedByVenue(UUID pitchId, String pitchName, Instant startsAt, Instant now) {
		if (CANCELLED.equals(status) || COMPLETED.equals(status) || !this.startsAt.isAfter(now)) {
			throw BusinessException.conflict("That game has already been played or called off, so it can’t be moved.");
		}
		this.pitchId = pitchId;
		this.pitchName = pitchName;
		this.startsAt = startsAt;
	}

	/**
	 * Anywhere the host types: a school field, a beach, a friend's court. {@code mapUrl} is optional:
	 * a Google Maps, Apple Maps or Waze link, or coordinates.
	 */
	public void playAt(String venueName, String venueArea, String mapUrl) {
		playAt(venueName, venueArea, mapUrl, null);
	}

	/**
	 * Anywhere the host types, with where it is on the map: a {@code pin} (as sent, or kept from the
	 * game this one repeats) gives the directions link in place of {@code mapUrl}.
	 */
	public void playAt(String venueName, String venueArea, String mapUrl, Pin pin) {
		var name = venueName == null ? "" : venueName.strip();
		if (name.isEmpty() || name.length() > 120) {
			throw BusinessException.invalid("Say where you’re playing.");
		}
		var area = venueArea == null ? "" : venueArea.strip();
		this.venueKind = UNLISTED;
		this.venueName = name;
		this.venueArea = area.isEmpty() ? null : area;
		this.pin = pin;
		this.mapUrl = this.pin != null && this.pin.located() ? this.pin.directions(area.isEmpty() ? name : name + ", " + area)
				: MapLink.normalise(mapUrl);
	}

	/** Joining is up to the player: allowed until kick-off, while there's a spot. */
	public void join(UUID userId, Instant now) {
		join(userId, null, now);
	}

	/** Takes a spot, on {@code teamId}'s side in a friendly (null: no side yet, the host picks on the day). */
	public void join(UUID userId, UUID teamId, Instant now) {
		if (spotOf(userId).isPresent()) {
			return;
		}
		requireOn("This game was called off by the host.");
		requireNotPlayed(now);
		requireNotFixture();
		if (isFull()) {
			throw BusinessException.conflict("Sorry, this game just filled up.");
		}
		var spot = Participant.player(this, userId, totalCost == 0, now);
		spot.playFor(isFriendly() ? teamId : null);
		participants.add(spot);
		syncStatus();
	}

	/** Puts a player on a side in a friendly (claiming a held spot, say). */
	public void playFor(UUID userId, UUID teamId) {
		if (isFriendly()) {
			spotOf(userId).ifPresent(p -> p.playFor(teamId));
		}
	}

	/**
	 * A player dropping out. Once they've paid, getting their money back comes first.
	 *
	 * <p>The notice they gave is written down ({@link Departure}): a spot given up a fortnight early
	 * and one given up an hour before are not the same thing, and once the spot is gone there is
	 * nothing left to tell them apart.
	 */
	public void leave(UUID userId, Instant now) {
		var spot = givingUpSpot(userId);
		if (spot == null) {
			return;
		}
		var departure = Departure.of(this, spot, Departure.LEFT, now);
		if (departure != null) {
			departures.add(departure);
		}
		participants.remove(spot);
		syncStatus();
	}

	/**
	 * Giving up a spot because the account is closing. Nothing is recorded: they are leaving
	 * PlayChale, not letting a game down, and what is already on their record goes with the account.
	 */
	public void giveUpSpotOnAccountClosed(UUID userId) {
		var spot = givingUpSpot(userId);
		if (spot == null) {
			return;
		}
		participants.remove(spot);
		syncStatus();
	}

	/** The checks both ways out of a game share. Null when there was no spot to give up. */
	private Participant givingUpSpot(UUID userId) {
		if (isHost(userId)) {
			throw BusinessException.conflict("Hosts can’t leave their own game.");
		}
		var spot = spotOf(userId);
		if (spot.isEmpty()) {
			return null;
		}
		if (spot.get().isPaid() && totalCost > 0) {
			throw BusinessException.conflict("You’ve already paid. Ask the host to sort out a refund.");
		}
		return spot.get();
	}

	/**
	 * The host taking someone off the roster before kick-off. Returns whether it was a player (who
	 * should be told) rather than a guest spot.
	 *
	 * @param playerName used in the message when they've already paid
	 */
	public boolean remove(Participant spot, UUID host, String playerName, Instant now) {
		if (spot.isPlayer(host)) {
			throw BusinessException.conflict("Hosts can’t remove themselves. Cancel the game instead.");
		}
		requireNotPlayed(now);
		if (!spot.isGuest() && spot.isPaid() && totalCost > 0) {
			throw BusinessException.conflict("%s has already paid. Sort out a refund with them first, then they can leave.".formatted(playerName));
		}
		// Recorded as the host's doing, so it never reads as the player letting anyone down.
		var departure = Departure.of(this, spot, Departure.REMOVED, now);
		if (departure != null) {
			departures.add(departure);
		}
		participants.remove(spot);
		syncStatus();
		return !spot.isGuest();
	}

	/** The host holding a spot for someone who isn't on PlayChale yet. */
	public Participant holdForGuest(String name, String phone, String claimHash, UUID host, Instant now) {
		return takeGuestSpot(name, phone, null, claimHash, host, now, "Give the player a name so everyone knows who’s in.",
				"The game is full. There’s no spot to hold.");
	}

	/**
	 * Someone without an account taking a spot themselves. Only in a public game (a private one is
	 * the host's to fill) and never a team's: a friendly is played by its two squads.
	 */
	public Participant joinAsGuest(String name, String phone, String email, String claimHash, Instant now) {
		if (!isPublic()) {
			throw BusinessException.conflict("This game is private. Ask the host to add you.");
		}
		if (isFriendly()) {
			throw BusinessException.conflict("This game is between two teams. Ask a captain to add you.");
		}
		return takeGuestSpot(name, phone, email, claimHash, null, now, "Give your name so everyone knows who’s in.",
				"The game is full.");
	}

	private Participant takeGuestSpot(String name, String phone, String email, String claimHash, UUID host, Instant now, String noName,
			String full) {
		var trimmed = name == null ? "" : name.strip();
		if (trimmed.isEmpty() || trimmed.length() > 60) {
			throw BusinessException.invalid(noName);
		}
		requireNotPlayed(now);
		requireOn("This game was called off.");
		requireNotFixture();
		if (isFull()) {
			throw BusinessException.conflict(full);
		}
		var spot = Participant.guest(this, trimmed, phone, email, claimHash, host, totalCost == 0, now);
		participants.add(spot);
		syncStatus();
		return spot;
	}

	/**
	 * A guest who took their own spot giving it up, before kick-off. Once the host has their cash, it
	 * goes through the host, as a player's paid spot does.
	 */
	public void guestLeaves(Participant spot, Instant now) {
		requireNotPlayed(now);
		if (spot.isPaid() && totalCost > 0) {
			throw BusinessException.conflict("The host has your money for this one. Ask them to take you off.");
		}
		participants.remove(spot);
		syncStatus();
	}

	/**
	 * Someone claiming a guest spot. For a spot the host held, the number they signed in with must
	 * match, if the host gave one. A spot they took themselves is theirs by its token, whatever they
	 * sign in with.
	 */
	public void claim(Participant spot, UUID userId, String userPhone) {
		if (spotOf(userId).isPresent()) {
			throw BusinessException.conflict("You’re already in this game.");
		}
		if (!spot.isGuestSelfJoined() && spot.getGuestPhone() != null && !spot.getGuestPhone().equals(userPhone)) {
			throw BusinessException.conflict("This invite was sent to a different number. Ask the host to add yours.");
		}
		spot.claimFor(userId);
	}

	/**
	 * Kick-off is in this local time: where it's played. Anything that isn't a real IANA zone is
	 * refused; left out, the country's own.
	 */
	public void keepTime(String timezone) {
		if (timezone == null || timezone.isBlank()) {
			this.timezone = Market.get(country).timezone();
			return;
		}
		try {
			this.timezone = ZoneId.of(timezone.strip()).getId();
		}
		catch (DateTimeException e) {
			throw BusinessException.invalid("Pick a time zone from the list.");
		}
	}

	/** Its country's market: money and phone numbers here go by it. */
	public Market market() {
		return Market.get(country);
	}

	public ZoneId zone() {
		return ZoneId.of(timezone);
	}

	/** What a player owes before paying in the app, in the game's own money. */
	public long shareDue(UUID userId) {
		return shareDue(userId, market());
	}

	/** What each spot pays, in the game's own money. */
	public long share() {
		return share(market());
	}

	/** What a player owes before paying in the app. */
	public long shareDue(UUID userId, Market market) {
		var spot = spotOf(userId).orElseThrow(() -> BusinessException.conflict("Join the game before paying your share."));
		if (totalCost == 0) {
			throw BusinessException.invalid("This game is free. There’s nothing to pay.");
		}
		requireOn("This game was called off, so there’s nothing to pay.");
		if (spot.isPaid()) {
			throw BusinessException.conflict("You’ve already paid your share.");
		}
		return share(market);
	}

	/**
	 * What each spot pays: the price, for a per-player game; otherwise the total shared by the spots,
	 * rounded up to the market's step. 0 for a free game.
	 */
	public long share(Market market) {
		if (totalCost == 0) {
			return 0;
		}
		return PER_PLAYER.equals(pricing) ? totalCost / capacity : market.shareOf(totalCost, capacity);
	}

	/** A booked partner pitch: its price is the cost, shared by the spots. */
	public void splitPitchCost() {
		this.pricing = SPLIT;
	}

	/** A player's in-app payment went through. */
	public void paidInApp(UUID userId, UUID paymentId) {
		spotOf(userId).ifPresent(spot -> spot.paidInApp(paymentId));
	}

	/** How many spots are paid for. */
	public int paidCount() {
		return (int) participants.stream().filter(Participant::isPaid).count();
	}

	/** The host recording that someone handed them their share in cash. */
	public void paidInCash(Participant spot) {
		spot.paidInCash();
	}

	/**
	 * The host saying who turned up, once the game has been played. Marking it again corrects it,
	 * because hosts get it wrong and a record nobody can fix is worse than no record.
	 */
	public void attended(Participant spot, boolean showedUp, Instant now) {
		if (!hasStarted(now)) {
			throw BusinessException.conflict("Wait until the game has been played, then say who turned up.");
		}
		if (isCancelled()) {
			throw BusinessException.conflict("This game was called off, so nobody was expected.");
		}
		spot.attended(showedUp, now);
	}

	/** Spots the host hasn't said either way about yet. Empty before kick-off. */
	public List<Participant> unmarked() {
		return participants.stream().filter(p -> p.getAttended() == null).toList();
	}

	/** Players who still owe their share (not guests, not the host), optionally only some of them. */
	public List<Participant> unpaid(Collection<UUID> only) {
		if (totalCost == 0) {
			throw BusinessException.invalid("This game is free. There’s nothing to pay.");
		}
		return participants.stream()
			.filter(p -> !p.isGuest() && !p.isPaid() && !p.isPlayer(hostId) && (only == null || only.contains(p.getUserId())))
			.toList();
	}

	public void reminded(List<Participant> spots, Instant now) {
		spots.forEach(p -> p.reminded(now));
	}

	/**
	 * The host calling the game off. Blocked while anyone but the host has paid, because refunds
	 * aren't in the app yet.
	 *
	 * @param paidNames first names of those who've paid, for the message
	 */
	public void cancel(String reason, List<String> paidNames, Instant now) {
		if (CANCELLED.equals(status)) {
			throw BusinessException.conflict("This game is already called off.");
		}
		if (COMPLETED.equals(status)) {
			throw BusinessException.conflict("This game has a result, so it can’t be called off.");
		}
		if (!paidNames.isEmpty() && totalCost > 0) {
			throw BusinessException.conflict("%s %s already paid. Refunds aren’t in the app yet, so sort that out with them first."
				.formatted(String.join(", ", paidNames), paidNames.size() == 1 ? "has" : "have"));
		}
		var note = reason == null ? "" : reason.strip();
		this.status = CANCELLED;
		this.cancelledAt = now;
		this.cancelReason = note.isEmpty() ? null : note.substring(0, Math.min(140, note.length()));
	}

	/**
	 * The host has recorded a result: the game is played. Only once it's kicked off, and never for a
	 * game that was called off.
	 */
	/**
	 * Puts a called-off fixture back on, so its result can still be recorded. A knockout tie has to
	 * produce a winner or the bracket stops there for good, so when one is rained off or a team
	 * doesn't show, an organiser settles it — a walkover, or the day it was finally played.
	 */
	public void reinstate() {
		if (!CANCELLED.equals(status)) {
			return;
		}
		if (competitionId == null) {
			throw BusinessException.conflict("Only a competition fixture can be put back on.");
		}
		status = OPEN;
		cancelledAt = null;
		cancelReason = null;
		syncStatus();
	}

	public void complete(Instant now) {
		if (CANCELLED.equals(status)) {
			throw BusinessException.conflict("This game was called off, so it has no result.");
		}
		if (!hasStarted(now)) {
			throw BusinessException.conflict("You can record the result once the game has started.");
		}
		status = COMPLETED;
	}

	/** Spots taken by someone other than the host who has paid. */
	public List<Participant> paidByOthers() {
		return participants.stream().filter(p -> p.isPaid() && !p.isPlayer(hostId)).toList();
	}

	public Optional<Participant> spotOf(UUID userId) {
		return participants.stream().filter(p -> p.isPlayer(userId)).findFirst();
	}

	/** A spot by the roster's key: a player's ID, or "guest:<spot id>". */
	public Optional<Participant> spotByKey(String key) {
		return participants.stream().filter(p -> p.playerKey().equals(key)).findFirst();
	}

	public Optional<Participant> guestSpotByClaimHash(String hash) {
		return participants.stream().filter(p -> p.isGuest() && hash.equals(p.getGuestClaimHash())).findFirst();
	}

	public boolean hasStarted(Instant now) {
		return !startsAt.isAfter(now);
	}

	public boolean isHost(UUID userId) {
		return hostId.equals(userId);
	}

	public boolean isCancelled() {
		return CANCELLED.equals(status);
	}

	/** A league fixture always is: its squads play it, however many are listed. */
	public boolean isFull() {
		return competitionId != null || participants.size() >= capacity;
	}

	/** None in a league fixture: its squads play it, and nobody joins one on their own. */
	public int spotsLeft() {
		return competitionId != null ? 0 : Math.max(0, capacity - participants.size());
	}

	/**
	 * A game between two teams: a league fixture, or a friendly the other team accepted. Its sides are
	 * the teams, so a result can be a score alone when no players are listed.
	 */
	public boolean isTeamGame() {
		return competitionId != null || opponentIn();
	}

	public Instant endsAt() {
		return startsAt.plus(Duration.ofMinutes(durationMinutes));
	}

	public Integer getFixtureSlot() {
		return fixtureSlot;
	}

	/** Whether this tie has to produce a winner, so a level score is settled on penalties. */
	public boolean isDecider() {
		return fixtureDecider;
	}

	/** Anyone can see a public game; a private one only its host and players (and anyone with the link). */
	public boolean isPublic() {
		return "public".equals(visibility);
	}

	private void requireOn(String cancelledMessage) {
		if (CANCELLED.equals(status)) {
			throw BusinessException.conflict(cancelledMessage);
		}
	}

	private void requireNotFixture() {
		if (competitionId != null) {
			throw BusinessException.conflict("Fixtures are played by the teams’ squads. Ask a captain for a place in theirs.");
		}
	}

	private void requireNotPlayed(Instant now) {
		if (COMPLETED.equals(status) || hasStarted(now)) {
			throw BusinessException.conflict("This game has already been played.");
		}
	}

	private void syncStatus() {
		if (COMPLETED.equals(status) || CANCELLED.equals(status)) {
			return;
		}
		status = isFull() ? FULL : OPEN;
	}

	public UUID getId() {
		return id;
	}

	public String getSport() {
		return sport;
	}

	public String getFormat() {
		return format;
	}

	public String getTitle() {
		return title;
	}

	public Instant getStartsAt() {
		return startsAt;
	}

	public int getDurationMinutes() {
		return durationMinutes;
	}

	public String getVenueKind() {
		return venueKind;
	}

	public UUID getVenueId() {
		return venueId;
	}

	public String getVenueName() {
		return venueName;
	}

	public String getVenueArea() {
		return venueArea;
	}

	public String getMapUrl() {
		return mapUrl;
	}

	public Pin getPin() {
		return pin;
	}

	public UUID getPitchId() {
		return pitchId;
	}

	public String getPitchName() {
		return pitchName;
	}

	public int getCapacity() {
		return capacity;
	}

	public long getTotalCost() {
		return totalCost;
	}

	public String getPricing() {
		return pricing;
	}

	public String getCurrency() {
		return currency;
	}

	public String getCountry() {
		return country;
	}

	public String getTimezone() {
		return timezone;
	}

	public String getVisibility() {
		return visibility;
	}

	public UUID getHostId() {
		return hostId;
	}

	public String getNotes() {
		return notes;
	}

	public String getStatus() {
		return status;
	}

	public Instant getCancelledAt() {
		return cancelledAt;
	}

	public String getCancelReason() {
		return cancelReason;
	}

	public UUID getCompetitionId() {
		return competitionId;
	}

	public Integer getFixtureRound() {
		return fixtureRound;
	}

	public UUID getHomeTeamId() {
		return homeTeamId;
	}

	public UUID getAwayTeamId() {
		return awayTeamId;
	}

	public String getOpponentStatus() {
		return opponentStatus;
	}

	public List<Participant> getParticipants() {
		return List.copyOf(participants);
	}

	/** Spots given up, oldest first. Who is in the game is {@link #getParticipants()}; this is history. */
	public List<Departure> getDepartures() {
		return List.copyOf(departures);
	}

	/** Everything needed to set the same game up again. */
	public GameDetails details() {
		return new GameDetails(sport, format, title, startsAt, durationMinutes, venueKind, venueId, pitchId, venueName,
				venueArea, mapUrl, capacity, totalCost, pricing, visibility, notes, pin);
	}

}
