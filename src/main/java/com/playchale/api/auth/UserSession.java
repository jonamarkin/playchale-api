package com.playchale.api.auth;

import java.time.Instant;

import com.playchale.api.users.User;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * A signed-in device. The browser holds a random token; only its hash is stored. Named UserSession
 * so it isn't confused with Hibernate's own Session.
 */
@Entity
@Table(name = "sessions")
class UserSession implements Persistable<String> {

	/** SHA-256 of the token, as hex. */
	@Id
	private String tokenHash;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	private User user;

	private Instant createdAt;

	private Instant expiresAt;

	private Instant lastSeenAt;

	/**
	 * The ID is set by us, not generated, so Spring Data can't tell a new session from an existing
	 * one by looking for a null ID. Without this flag save() would SELECT before every INSERT.
	 */
	@Transient
	private boolean isNew = true;

	protected UserSession() {
	}

	UserSession(String tokenHash, User user, Instant now, Instant expiresAt) {
		this.tokenHash = tokenHash;
		this.user = user;
		this.createdAt = now;
		this.lastSeenAt = now;
		this.expiresAt = expiresAt;
	}

	User user() {
		return user;
	}

	/** Remembering when a device was last used lets old sessions be tidied up later. */
	void seen(Instant now) {
		lastSeenAt = now;
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
