package com.playchale.api.games.internal.domain;

import java.time.Duration;
import java.time.Instant;
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
import com.playchale.api.shared.persistence.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
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
	 * A map link for directions, for a game anywhere but a partner venue (see MapLink). A partner
	 * venue's own link is looked up instead, so this stays null for those.
	 */
	private String mapUrl;

	private UUID pitchId;

	private String pitchName;

	private int capacity;

	private long totalCost;

	/** {@link #SPLIT} or {@link #PER_PLAYER}: how the cost is set, and so how the app describes it. */
	private String pricing = SPLIT;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 3)
	private String currency;

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

	@OneToMany(mappedBy = "game", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("joinedAt")
	private List<Participant> participants = new ArrayList<>();

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
	 * A league fixture. The organiser hosts it, the two squads fill it, and nobody pays through the
	 * app for a league game.
	 */
	public static Game fixture(UUID competitionId, int round, UUID homeTeamId, UUID awayTeamId, String title, String sport,
			String format, Instant startsAt, int durationMinutes, UUID organiserId, List<UUID> squad, Market market, Instant now) {
		var game = new Game();
		game.sport = sport;
		game.format = format;
		game.title = title;
		game.startsAt = startsAt;
		game.durationMinutes = durationMinutes;
		game.totalCost = 0;
		game.currency = market.currency();
		game.visibility = "public";
		game.hostId = organiserId;
		game.competitionId = competitionId;
		game.fixtureRound = round;
		game.homeTeamId = homeTeamId;
		game.awayTeamId = awayTeamId;
		game.capacity = Math.max(2, squad.size());
		squad.stream().distinct().forEach(player -> game.participants.add(Participant.player(game, player, true, now)));
		game.syncStatus();
		return game;
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
		var name = venueName == null ? "" : venueName.strip();
		if (name.isEmpty() || name.length() > 120) {
			throw BusinessException.invalid("Say where you’re playing.");
		}
		var area = venueArea == null ? "" : venueArea.strip();
		this.venueKind = UNLISTED;
		this.venueName = name;
		this.venueArea = area.isEmpty() ? null : area;
		this.mapUrl = MapLink.normalise(mapUrl);
	}

	/** Joining is up to the player: allowed until kick-off, while there's a spot. */
	public void join(UUID userId, Instant now) {
		if (spotOf(userId).isPresent()) {
			return;
		}
		requireOn("This game was called off by the host.");
		requireNotPlayed(now);
		if (isFull()) {
			throw BusinessException.conflict("Sorry, this game just filled up.");
		}
		participants.add(Participant.player(this, userId, totalCost == 0, now));
		syncStatus();
	}

	/** A player dropping out. Once they've paid, getting their money back comes first. */
	public void leave(UUID userId) {
		if (isHost(userId)) {
			throw BusinessException.conflict("Hosts can’t leave their own game.");
		}
		var spot = spotOf(userId);
		if (spot.isEmpty()) {
			return;
		}
		if (spot.get().isPaid() && totalCost > 0) {
			throw BusinessException.conflict("You’ve already paid. Ask the host to sort out a refund.");
		}
		participants.remove(spot.get());
		syncStatus();
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
		participants.remove(spot);
		syncStatus();
		return !spot.isGuest();
	}

	/** The host holding a spot for someone who isn't on PlayChale yet. */
	public Participant holdForGuest(String name, String phone, String claimHash, UUID host, Instant now) {
		var trimmed = name == null ? "" : name.strip();
		if (trimmed.isEmpty() || trimmed.length() > 60) {
			throw BusinessException.invalid("Give the player a name so everyone knows who’s in.");
		}
		requireNotPlayed(now);
		requireOn("This game was called off.");
		if (isFull()) {
			throw BusinessException.conflict("The game is full. There’s no spot to hold.");
		}
		var spot = Participant.guest(this, trimmed, phone, claimHash, host, totalCost == 0, now);
		participants.add(spot);
		syncStatus();
		return spot;
	}

	/** Someone claiming the spot the host held for them. The number they signed in with must match, if the host gave one. */
	public void claim(Participant spot, UUID userId, String userPhone) {
		if (spotOf(userId).isPresent()) {
			throw BusinessException.conflict("You’re already in this game.");
		}
		if (spot.getGuestPhone() != null && !spot.getGuestPhone().equals(userPhone)) {
			throw BusinessException.conflict("This invite was sent to a different number. Ask the host to add yours.");
		}
		spot.claimFor(userId);
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

	public boolean isFull() {
		return participants.size() >= capacity;
	}

	public int spotsLeft() {
		return Math.max(0, capacity - participants.size());
	}

	public Instant endsAt() {
		return startsAt.plus(Duration.ofMinutes(durationMinutes));
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

	public List<Participant> getParticipants() {
		return List.copyOf(participants);
	}

	/** Everything needed to set the same game up again. */
	public GameDetails details() {
		return new GameDetails(sport, format, title, startsAt, durationMinutes, venueKind, venueId, pitchId, venueName,
				venueArea, mapUrl, capacity, totalCost, pricing, visibility, notes);
	}

}
