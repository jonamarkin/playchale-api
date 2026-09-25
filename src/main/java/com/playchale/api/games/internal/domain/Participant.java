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
 * A spot in a game: a player, or a guest the host is holding it for until they claim it with their
 * own number. Part of its {@link Game}, and only changed through it.
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

	static Participant guest(Game game, String name, String phone, String claimHash, UUID addedBy, boolean paid, Instant now) {
		var p = new Participant();
		p.game = game;
		p.guestName = name;
		p.guestPhone = phone;
		p.guestClaimHash = claimHash;
		p.guestAddedBy = addedBy;
		p.paid = paid;
		p.joinedAt = now;
		return p;
	}

	/** The guest's spot becomes theirs: from now on it's an ordinary player's spot. */
	void claimFor(UUID userId) {
		this.userId = userId;
		this.guestName = null;
		this.guestPhone = null;
		this.guestClaimHash = null;
		this.guestAddedBy = null;
	}

	void paidInCash() {
		paid = true;
		paidVia = PAID_IN_CASH;
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

	public UUID getGuestAddedBy() {
		return guestAddedBy;
	}

}
