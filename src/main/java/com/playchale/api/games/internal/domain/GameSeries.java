package com.playchale.api.games.internal.domain;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import com.playchale.api.market.Market;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.shared.maps.Pin;
import com.playchale.api.shared.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * A game that repeats. It holds what each of its games is set up from and when the next one opens;
 * the games themselves are ordinary games that belong to it.
 *
 * <p>One is open at a time. The next opens when the last one ends, at the next of the series' days
 * at least {@link #LEAD} away, and invites the last one's players. A date it can't open (its pitch
 * taken) is skipped, and it carries on with the one after. Calling a game off is how a host skips a
 * week: the series carries on regardless.
 */
@Entity
@Table(name = "game_series")
public class GameSeries extends AuditableEntity {

	public static final String ACTIVE = "active";

	public static final String PAUSED = "paused";

	public static final String STOPPED = "stopped";

	/** A game opens at least this long before kick-off. Any closer and it's the date after: nobody can plan around an hour's notice. */
	public static final Duration LEAD = Duration.ofHours(12);

	/** Played games in a row with nobody but the host before the series pauses, rather than open games nobody comes to forever. */
	static final int EMPTY_RUNS_TO_PAUSE = 2;

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	private UUID hostId;

	private String sport;

	private String format;

	private String title;

	private int durationMinutes;

	private String venueKind;

	private UUID venueId;

	private UUID pitchId;

	private String venueName;

	private String venueArea;

	private String mapUrl;

	/** Where it's played on the map, for a place that isn't a partner venue: copied to each game it opens. */
	@Embedded
	private Pin pin;

	private int capacity;

	private long totalCost;

	private String pricing;

	private String visibility;

	private String notes;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 2)
	private String country;

	private String timezone;

	private String frequency;

	/** ISO: 1 is Monday. */
	private int weekday;

	private Integer weekOfMonth;

	private LocalTime kickOff;

	private Instant nextStartsAt;

	private Instant opensAt;

	private UUID lastGameId;

	private int emptyRuns;

	private boolean lastGameCounted;

	private String status;

	private String pausedReason;

	private Instant stoppedAt;

	protected GameSeries() {
	}

	/** A series carrying {@code first} on: set up from it, with its next game on the first of the rule's days after it. */
	public GameSeries(Game first, SeriesRule rule) {
		if (first.getCompetitionId() != null) {
			throw BusinessException.conflict("A fixture is scheduled by its competition, so it can’t repeat on its own.");
		}
		if (first.isFriendly()) {
			throw BusinessException.conflict("A team game can’t repeat on its own. Use “Same again next week” to challenge them again.");
		}
		if (first.getSeriesId() != null) {
			throw BusinessException.conflict("This game already repeats.");
		}
		if (!rule.zone().getId().equals(first.getTimezone())) {
			throw new IllegalArgumentException("A series runs in its first game's time");
		}
		var details = first.details();
		this.hostId = first.getHostId();
		this.sport = details.sport();
		this.format = details.format();
		this.title = details.title();
		this.durationMinutes = details.durationMinutes();
		this.venueKind = details.venueKind();
		this.venueId = details.venueId();
		this.pitchId = details.pitchId();
		this.venueName = details.venueName();
		this.venueArea = details.venueArea();
		this.mapUrl = details.venueMapUrl();
		this.pin = details.venuePin();
		this.capacity = details.capacity();
		this.totalCost = details.totalCost();
		this.pricing = details.pricing();
		this.visibility = details.visibility();
		this.notes = details.notes();
		this.country = first.getCountry();
		this.timezone = first.getTimezone();
		follow(rule);
		this.status = ACTIVE;
		opened(first);
	}

	/** When it's played. */
	public SeriesRule rule() {
		return new SeriesRule(frequency, DayOfWeek.of(weekday), weekOfMonth, kickOff, ZoneId.of(timezone));
	}

	/** The game to open at {@code startsAt}, as the host set the series up. */
	public GameDetails detailsAt(Instant startsAt) {
		return new GameDetails(sport, format, title, startsAt, durationMinutes, venueKind, venueId, pitchId, venueName, venueArea, mapUrl,
				capacity, totalCost, pricing, visibility, notes, pin);
	}

	/** Whether it's time to open the next game. */
	public boolean isDue(Instant now) {
		return ACTIVE.equals(status) && !opensAt.isAfter(now);
	}

	/** The next game's kick-off, opening now: the planned one, or the first after it far enough away. */
	public Instant nextDate(Instant now) {
		return rule().onOrAfter(nextStartsAt, now.plus(LEAD));
	}

	/**
	 * Counts the last game, once it's over. Returns true when the series should pause instead of
	 * opening another: the last two played had nobody but the host. A called-off game says nothing
	 * either way, so it doesn't count.
	 */
	public boolean countLastGame(Game last) {
		if (last != null && !lastGameCounted && last.getId().equals(lastGameId)) {
			if (!last.isCancelled()) {
				emptyRuns = last.getParticipants().size() <= 1 ? emptyRuns + 1 : 0;
			}
			lastGameCounted = true;
		}
		return emptyRuns >= EMPTY_RUNS_TO_PAUSE;
	}

	/** {@code game} is its latest: the next is the first of its days after it, opening when it ends. */
	public void opened(Game game) {
		this.lastGameId = game.getId();
		this.lastGameCounted = false;
		this.nextStartsAt = rule().at(rule().after(game.getStartsAt().atZone(zone()).toLocalDate()));
		this.opensAt = game.endsAt();
	}

	/** The game at {@code startsAt} couldn't be opened. The series carries on with the one after, once that date has passed. */
	public void missed(Instant startsAt) {
		this.nextStartsAt = rule().at(rule().after(startsAt.atZone(zone()).toLocalDate()));
		this.opensAt = startsAt.plus(Duration.ofMinutes(durationMinutes));
	}

	/** Its latest game moved (the venue moved the booking): the next opens when it now ends. */
	public void lastGameMoved(Game game) {
		if (game.getId().equals(lastGameId)) {
			this.opensAt = game.endsAt();
		}
	}

	public void pause(String reason) {
		this.status = PAUSED;
		this.pausedReason = reason;
	}

	public void stop(Instant now) {
		if (STOPPED.equals(status)) {
			throw BusinessException.conflict("This game has already stopped repeating.");
		}
		this.status = STOPPED;
		this.stoppedAt = now;
		this.pausedReason = null;
	}

	/**
	 * Back on, after a pause or a stop. Nothing that happened before counts against it, and the next
	 * game opens now, or when the one still to come ends.
	 */
	public void restart(Game last, Instant now) {
		if (ACTIVE.equals(status)) {
			throw BusinessException.conflict("This game is already repeating.");
		}
		this.status = ACTIVE;
		this.pausedReason = null;
		this.stoppedAt = null;
		this.emptyRuns = 0;
		this.lastGameCounted = true;
		this.opensAt = last != null && last.endsAt().isAfter(now) ? last.endsAt() : now;
	}

	/**
	 * The host changing how it repeats or what its games are. It applies from the next game opened:
	 * one already open stays as it is. The details are checked as a game would check them, now,
	 * rather than when the next one fails to open.
	 *
	 * @param open the game still to come, if there is one: the next is after it
	 */
	public void change(SeriesChange change, Game open, Instant now) {
		var rule = new SeriesRule(change.frequency(), DayOfWeek.of(checkedWeekday(change.weekday())), change.weekOfMonth(), change.kickOff(),
				zone());
		var details = new GameDetails(sport, format, change.title() == null || change.title().isBlank() ? title : change.title().strip(),
				now.plus(LEAD).plus(Duration.ofDays(1)), change.durationMinutes(), venueKind, venueId, pitchId, venueName, venueArea, mapUrl,
				change.capacity(), change.totalCost(), change.pricing(), change.visibility(), change.notes(), pin);
		Game.check(details, hostId, Market.get(country), now);
		this.title = details.title();
		this.durationMinutes = details.durationMinutes();
		this.capacity = details.capacity();
		this.totalCost = details.totalCost();
		this.pricing = details.totalCost() == 0 || details.pricing() == null ? Game.SPLIT : details.pricing();
		this.visibility = details.visibility();
		this.notes = details.notes() == null || details.notes().isBlank() ? null : details.notes().strip();
		follow(rule);
		// After the game still to come, a whole week (or two, or a month) on; with none, the soonest.
		this.nextStartsAt = open != null && open.getStartsAt().isAfter(now) ? rule.at(rule.after(open.getStartsAt().atZone(zone()).toLocalDate()))
				: rule.onOrAfter(rule.at(rule.onOrAfter(now.atZone(zone()).toLocalDate())), now.plus(LEAD));
	}

	private static int checkedWeekday(int weekday) {
		if (weekday < 1 || weekday > 7) {
			throw BusinessException.invalid("Pick the day it’s played.");
		}
		return weekday;
	}

	private void follow(SeriesRule rule) {
		this.frequency = rule.frequency();
		this.weekday = rule.weekday().getValue();
		this.weekOfMonth = rule.weekOfMonth();
		this.kickOff = rule.kickOff();
	}

	private ZoneId zone() {
		return ZoneId.of(timezone);
	}

	public boolean isHost(UUID userId) {
		return hostId.equals(userId);
	}

	public boolean isActive() {
		return ACTIVE.equals(status);
	}

	public UUID getId() {
		return id;
	}

	public UUID getHostId() {
		return hostId;
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

	public int getDurationMinutes() {
		return durationMinutes;
	}

	public String getVenueKind() {
		return venueKind;
	}

	public UUID getVenueId() {
		return venueId;
	}

	public UUID getPitchId() {
		return pitchId;
	}

	public String getVenueName() {
		return venueName;
	}

	public String getVenueArea() {
		return venueArea;
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

	public String getVisibility() {
		return visibility;
	}

	public String getNotes() {
		return notes;
	}

	public String getCountry() {
		return country;
	}

	public String getTimezone() {
		return timezone;
	}

	public String getFrequency() {
		return frequency;
	}

	public int getWeekday() {
		return weekday;
	}

	public Integer getWeekOfMonth() {
		return weekOfMonth;
	}

	public LocalTime getKickOff() {
		return kickOff;
	}

	public Instant getNextStartsAt() {
		return nextStartsAt;
	}

	public Instant getOpensAt() {
		return opensAt;
	}

	public UUID getLastGameId() {
		return lastGameId;
	}

	public String getStatus() {
		return status;
	}

	public String getPausedReason() {
		return pausedReason;
	}

}
