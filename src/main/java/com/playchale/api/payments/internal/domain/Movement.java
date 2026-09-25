package com.playchale.api.payments.internal.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * One movement of money from one person's point of view: a line on their statement. Written once and
 * never changed; a mistake is put right with another movement.
 */
@Entity
@Table(name = "movements")
@Immutable
public class Movement {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	private UUID userId;

	private String direction;

	private String kind;

	private long amount;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 3)
	private String currency;

	private UUID gameId;

	private UUID counterpartyId;

	private String method;

	private String reference;

	private UUID paymentId;

	private String status;

	private Instant createdAt;

	protected Movement() {
	}

	private Movement(UUID userId, String direction, UUID gameId, UUID counterpartyId, long amount, String currency, String method,
			String reference, UUID paymentId, Instant now) {
		this.userId = userId;
		this.direction = direction;
		this.kind = "share";
		this.amount = amount;
		this.currency = currency;
		this.gameId = gameId;
		this.counterpartyId = counterpartyId;
		this.method = method;
		this.reference = reference;
		this.paymentId = paymentId;
		this.status = "settled";
		this.createdAt = now;
	}

	/** A share leaving the payer's hands for the host. */
	public static Movement shareOut(UUID payer, UUID host, UUID gameId, long amount, String currency, String method, String reference,
			UUID paymentId, Instant now) {
		return new Movement(payer, "out", gameId, host, amount, currency, method, reference, paymentId, now);
	}

	/** A share reaching the host. {@code payer} is null for a guest, who has no account. */
	public static Movement shareIn(UUID host, UUID payer, UUID gameId, long amount, String currency, String method, String reference,
			UUID paymentId, Instant now) {
		return new Movement(host, "in", gameId, payer, amount, currency, method, reference, paymentId, now);
	}

	public UUID getId() {
		return id;
	}

	public UUID getUserId() {
		return userId;
	}

	public String getDirection() {
		return direction;
	}

	public String getKind() {
		return kind;
	}

	public long getAmount() {
		return amount;
	}

	public String getCurrency() {
		return currency;
	}

	public UUID getGameId() {
		return gameId;
	}

	public UUID getCounterpartyId() {
		return counterpartyId;
	}

	public String getMethod() {
		return method;
	}

	public String getReference() {
		return reference;
	}

	public String getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
