package com.playchale.api.games.internal.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

/**
 * A spot in a game: a player, or a guest: someone the host is holding it for, or someone who took it
 * without an account. A guest's spot becomes theirs when they claim it. Part of its {@link Game},
 * and only changed through it.
 */
@Entity
@Table(name = "game_participants")
public class Participant {

	public static final String PAID_IN_APP = "app";

	public static final String PAID_IN_CASH = "cash";

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	private Game game;

	/** Null while the spot is held for a guest. */
	private UUID userId;

	private Instant joinedAt;

	private boolean paid;

	private String paidVia;

	private UUID paymentId;

	private Instant remindedAt;

	private String guestName;

	private String guestPhone;

	/** SHA-256 of the claim link's token; the token itself is never stored. */
	private String guestClaimHash;

	private UUID guestAddedBy;

	/** A guest's email, lower-case, if they gave one. Only shown to the host. */
	private String guestEmail;

	/** Whether the guest took the spot themselves, rather than the host holding it for them. */
	private boolean guestSelfJoined;

	/** The side they play for in a friendly: the home or away team. Null elsewhere. */
	private UUID teamId;

	/**
	 * Whether they turned up, once the host has said. Null until then: not marked and did not turn
	 * up are different things, and most games will never be marked at all.
	 */
	private Boolean attended;

	private Instant attendedAt;

	protected Participant() {
	}

	static Participant player(Game game, UUID userId, boolean paid, Instant now) {
		var p = new Participant();
		p.game = game;
		p.userId = userId;
		p.paid = paid;
		p.joinedAt = now;
		return p;
	}

	static Participant guest(Game game, String name, String phone, String email, String claimHash, UUID addedBy, boolean paid,
			Instant now) {
		var p = new Participant();
		p.game = game;
		p.guestName = name;
		p.guestPhone = phone;
		p.guestEmail = email;
		p.guestClaimHash = claimHash;
		p.guestAddedBy = addedBy;
		// Nobody held it for them: they took it.
		p.guestSelfJoined = addedBy == null;
		p.paid = paid;
		p.joinedAt = now;
		return p;
	}

	void playFor(UUID teamId) {
		this.teamId = teamId;
	}

	public UUID getTeamId() {
		return teamId;
	}

	/** The guest's spot becomes theirs: from now on it's an ordinary player's spot. */
	void claimFor(UUID userId) {
		this.userId = userId;
		this.guestName = null;
		this.guestPhone = null;
		this.guestClaimHash = null;
		this.guestAddedBy = null;
		this.guestEmail = null;
		this.guestSelfJoined = false;
	}

	void paidInApp(UUID paymentId) {
		paid = true;
		paidVia = PAID_IN_APP;
		this.paymentId = paymentId;
	}

	void paidInCash() {
		paid = true;
		paidVia = PAID_IN_CASH;
	}

	/** The game became free (nothing to pay) or started costing (a share to pay). Someone who really paid stays paid. */
	void costs(boolean free) {
		if (paidVia == null) {
			paid = free;
		}
	}

	/** The host saying whether they turned up. Marking it again corrects it. */
	void attended(boolean showedUp, Instant now) {
		attended = showedUp;
		attendedAt = now;
	}

	/** Null when the host hasn't said, which is not the same as a no-show. */
	public Boolean getAttended() {
		return attended;
	}

	public Instant getAttendedAt() {
		return attendedAt;
	}

	void reminded(Instant now) {
		remindedAt = now;
	}

	public boolean isGuest() {
		return userId == null;
	}

	public boolean isPlayer(UUID user) {
		return user != null && user.equals(userId);
	}

	/** What the roster calls them: the player's ID, or "guest:<spot id>" for a guest. */
	public String playerKey() {
		return isGuest() ? "guest:" + id : userId.toString();
	}

	public UUID getId() {
		return id;
	}

	public UUID getUserId() {
		return userId;
	}

	public Instant getJoinedAt() {
		return joinedAt;
	}

	public boolean isPaid() {
		return paid;
	}

	public String getPaidVia() {
		return paidVia;
	}

	public UUID getPaymentId() {
		return paymentId;
	}

	public Instant getRemindedAt() {
		return remindedAt;
	}

	public String getGuestName() {
		return guestName;
	}

	public String getGuestPhone() {
		return guestPhone;
	}

	String getGuestClaimHash() {
		return guestClaimHash;
	}

	public String getGuestEmail() {
		return guestEmail;
	}

	public boolean isGuestSelfJoined() {
		return guestSelfJoined;
	}

	public UUID getGuestAddedBy() {
		return guestAddedBy;
	}

}
