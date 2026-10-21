package com.playchale.api.notifications.internal.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

/** One phone with the PlayChale app, by the push token the app registered, and whose it is. */
@Entity
@Table(name = "app_push_tokens")
public class AppPushToken {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	private UUID userId;

	private String token;

	private String platform;

	private Instant createdAt;

	private Instant lastSentAt;

	protected AppPushToken() {
	}

	public AppPushToken(UUID userId, String token, String platform, Instant now) {
		this.userId = userId;
		this.token = token;
		this.platform = platform;
		this.createdAt = now;
	}

	/** The same phone again, maybe someone else signed in on it now. */
	public void renew(UUID userId, String platform) {
		this.userId = userId;
		this.platform = platform;
	}

	public void sent(Instant now) {
		this.lastSentAt = now;
	}

	public UUID getId() {
		return id;
	}

	public UUID getUserId() {
		return userId;
	}

	public String getToken() {
		return token;
	}

}
