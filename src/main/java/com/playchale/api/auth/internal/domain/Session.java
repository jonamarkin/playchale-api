package com.playchale.api.auth.internal.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * A signed-in device, known by the hash of its token. The player is held by ID, not as a link to
 * the users module's entity: modules never reach into each other's tables.
 */
@Entity
@Table(name = "sessions")
public class Session implements Persistable<String> {

	public static final Duration LIFETIME = Duration.ofDays(30);

	/** How precisely "last seen" is kept. Hourly saves a database write on nearly every request. */
	static final Duration SEEN_PRECISION = Duration.ofHours(1);

	/** SHA-256 of the token, as hex. */
	@Id
	private String tokenHash;

	private UUID userId;

	private Instant createdAt;

	private Instant expiresAt;

	private Instant lastSeenAt;

	/**
	 * The ID is set by us, not generated, so Spring Data can't tell a new session from a stored one
	 * by looking for a null ID. Without this flag save() would SELECT before every INSERT.
	 */
	@Transient
	private boolean isNew = true;

	protected Session() {
	}

	/** A new session for a player who just proved they hold their phone. */
	public Session(SessionToken token, UUID userId, Instant now) {
		this.tokenHash = token.hash();
		this.userId = userId;
		this.createdAt = now;
		this.expiresAt = now.plus(LIFETIME);
		this.lastSeenAt = now;
	}

	/** Notes that the device was used, which lets old sessions be tidied up later. */
	public void seen(Instant now) {
		if (lastSeenAt.isBefore(now.minus(SEEN_PRECISION))) {
			lastSeenAt = now;
		}
	}

	public UUID getUserId() {
		return userId;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getLastSeenAt() {
		return lastSeenAt;
	}

	@Override
	public String getId() {
		return tokenHash;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	@PostLoad
	@PostPersist
	void stored() {
		isNew = false;
	}

}
