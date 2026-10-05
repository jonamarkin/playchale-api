package com.playchale.api.games.internal.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

/**
 * Someone who was in a game and then wasn't, and how much notice that gave. Part of its {@link Game},
 * and only written through it.
 *
 * <p>This is a record of what happened, never a judgement on it: a late drop-out and an emergency
 * look the same from here, so the app shows the fact ("left three hours before kick-off") and leaves
 * the reading of it to whoever is looking. See RELIABILITY.md.
 */
@Entity
@Table(name = "game_departures")
public class Departure {

	/** They gave the spot up themselves: the only reason that says anything about them. */
	public static final String LEFT = "left";

	/** The host took them off the roster, which is the host's doing. */
	public static final String REMOVED = "removed";

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	private Game game;

	private UUID userId;

	private Instant joinedAt;

	private Instant leftAt;

	private Instant startsAt;

	private int noticeMinutes;

	private String reason;

	private Instant createdAt;

	protected Departure() {
	}

	private Departure(Game game, Participant spot, String reason, Instant now) {
		this.game = game;
		this.userId = spot.getUserId();
		this.joinedAt = spot.getJoinedAt();
		this.leftAt = now;
		this.startsAt = game.getStartsAt();
		this.noticeMinutes = (int) Duration.between(now, game.getStartsAt()).toMinutes();
		this.reason = reason;
		this.createdAt = now;
	}

	/**
	 * Records a spot being given up. Guests have no account to hold it against, so they are skipped:
	 * the host held the spot and the host gave it back.
	 *
	 * @return null when there is nobody to record it for
	 */
	static Departure of(Game game, Participant spot, String reason, Instant now) {
		return spot.isGuest() ? null : new Departure(game, spot, reason, now);
	}

	public UUID getUserId() {
		return userId;
	}

	public Instant getLeftAt() {
		return leftAt;
	}

	/** Minutes between leaving and kick-off; negative if they left after it had passed. */
	public int getNoticeMinutes() {
		return noticeMinutes;
	}

	public String getReason() {
		return reason;
	}

	/** Whether they gave the spot up themselves, rather than the host taking it back. */
	public boolean isOwnDoing() {
		return LEFT.equals(reason);
	}

}
