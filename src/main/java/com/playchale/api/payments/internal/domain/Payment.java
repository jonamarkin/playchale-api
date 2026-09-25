package com.playchale.api.payments.internal.domain;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.playchale.api.shared.error.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** One attempt to collect a player's share: pending while they approve it, then succeeded or failed. */
@Entity
@Table(name = "payments")
public class Payment {

	public static final String PENDING = "pending";

	public static final String SUCCEEDED = "succeeded";

	public static final String FAILED = "failed";

	public static final String CARD = "card";

	static final Duration FIRST_CHECK_AFTER = Duration.ofMinutes(1);

	static final Set<String> METHODS = Set.of("momo-mtn", "momo-telecel", "momo-at", CARD);

	private static final String REFERENCE_CHARACTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

	private static final SecureRandom random = new SecureRandom();

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	private UUID gameId;

	private UUID userId;

	private String method;

	private long amount;

	private long fee;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 3)
	private String currency;

	private String status;

	private String reference;

	private String payerPhone;

	private String failureReason;

	private Instant createdAt;

	private Instant settledAt;

	/** How many times the background worker has asked the provider about it. */
	private int checks;

	/** When the worker should next ask the provider, while it's pending. */
	private Instant nextCheckAt;

	/** The provider's checkout page, for a hosted checkout. */
	private String checkoutUrl;

	protected Payment() {
	}

	/**
	 * A new attempt.
	 *
	 * @param payerPhone  already normalised (E.164), or null
	 * @param phoneNeeded whether mobile money needs the number up front (it doesn't when the payer gives
	 *                    it on the provider's own checkout page)
	 */
	public Payment(UUID gameId, UUID userId, String method, long amount, String currency, String payerPhone, boolean phoneNeeded,
			Instant now) {
		if (method == null || !METHODS.contains(method)) {
			throw BusinessException.invalid("Pick how you’ll pay.");
		}
		if (!CARD.equals(method) && phoneNeeded && payerPhone == null) {
			throw BusinessException.invalid("Enter the mobile money number to charge.");
		}
		this.gameId = gameId;
		this.userId = userId;
		this.method = method;
		this.amount = amount;
		this.currency = currency;
		this.payerPhone = CARD.equals(method) ? null : payerPhone;
		this.status = PENDING;
		this.reference = newReference();
		this.createdAt = now;
		// The payer's app polls for the first minute; after that the background worker takes over.
		this.nextCheckAt = now.plus(FIRST_CHECK_AFTER);
	}

	/** The payer goes to the provider's page to pay. */
	public void sendToCheckout(String url) {
		checkoutUrl = url;
	}

	public void succeed(Instant now) {
		status = SUCCEEDED;
		settledAt = now;
	}

	public void fail(String reason, Instant now) {
		status = FAILED;
		failureReason = reason;
		settledAt = now;
	}

	public boolean isPending() {
		return PENDING.equals(status);
	}

	public String getCheckoutUrl() {
		return checkoutUrl;
	}

	public int getChecks() {
		return checks;
	}

	public boolean belongsTo(UUID user) {
		return userId.equals(user);
	}

	/** "PC-7K2M9Q": short enough to read out over the phone, without letters that look like digits. */
	private static String newReference() {
		var reference = new StringBuilder("PC-");
		for (int i = 0; i < 8; i++) {
			reference.append(REFERENCE_CHARACTERS.charAt(random.nextInt(REFERENCE_CHARACTERS.length())));
		}
		return reference.toString();
	}

	public UUID getId() {
		return id;
	}

	public UUID getGameId() {
		return gameId;
	}

	public UUID getUserId() {
		return userId;
	}

	public String getMethod() {
		return method;
	}

	public long getAmount() {
		return amount;
	}

	public long getFee() {
		return fee;
	}

	public String getCurrency() {
		return currency;
	}

	public String getStatus() {
		return status;
	}

	public String getReference() {
		return reference;
	}

	public String getPayerPhone() {
		return payerPhone;
	}

	public String getFailureReason() {
		return failureReason;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
