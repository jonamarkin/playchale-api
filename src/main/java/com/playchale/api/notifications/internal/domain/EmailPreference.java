package com.playchale.api.notifications.internal.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * What someone agreed to receive by email.
 *
 * <p>Two kinds of mail, kept apart on purpose. Service email is about something they are already in
 * — a game, a league they entered, their sign-in code — and needs no agreement, though they can keep
 * a whole category out of their inbox. Marketing email (the weekly digest, anything about PlayChale
 * itself) is off until they turn it on, and {@code optedInAt}/{@code optedOutAt} keep the record of
 * when they did: that, rather than the current setting, is what an enquiry asks for, so neither is
 * ever cleared.
 *
 * <p>The token is what makes one tap in an email enough to stop it, with nobody signed in. Rotating
 * it makes every link in every email already sent stop working.
 */
@Entity
@Table(name = "email_preferences")
public class EmailPreference {

	@Id
	private UUID userId;

	/** A Postgres text[] column, read and written whole. */
	@JdbcTypeCode(SqlTypes.ARRAY)
	private List<String> muted = new ArrayList<>();

	private boolean marketing;

	private String digest = "weekly";

	private Instant optedInAt;

	private Instant optedOutAt;

	private UUID token;

	private Instant updatedAt;

	protected EmailPreference() {
	}

	public EmailPreference(UUID userId, Instant now) {
		this.userId = userId;
		this.token = UUID.randomUUID();
		this.updatedAt = now;
	}

	public void mute(List<String> categories) {
		this.muted = new ArrayList<>(categories);
	}

	/** Turning marketing on or off, with the date it happened. */
	public void setMarketing(boolean wanted, Instant now) {
		if (wanted == this.marketing) {
			return;
		}
		this.marketing = wanted;
		if (wanted) {
			this.optedInAt = now;
		}
		else {
			this.optedOutAt = now;
		}
		this.updatedAt = now;
	}

	public void setDigest(String choice, Instant now) {
		this.digest = choice;
		this.updatedAt = now;
	}

	/** Everything consent-gated, off at once: what the link in an email does. */
	public void unsubscribeAll(Instant now) {
		setMarketing(false, now);
		setDigest("off", now);
	}

	/** A new token, so links already in people's inboxes stop working. */
	public void rotateToken(Instant now) {
		this.token = UUID.randomUUID();
		this.updatedAt = now;
	}

	public UUID getUserId() {
		return userId;
	}

	public List<String> getMuted() {
		return List.copyOf(muted);
	}

	public boolean isMarketing() {
		return marketing;
	}

	public String getDigest() {
		return digest;
	}

	public boolean wantsDigest() {
		return "weekly".equals(digest);
	}

	public Instant getOptedInAt() {
		return optedInAt;
	}

	public Instant getOptedOutAt() {
		return optedOutAt;
	}

	public UUID getToken() {
		return token;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
