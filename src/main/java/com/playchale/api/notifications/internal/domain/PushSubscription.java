package com.playchale.api.notifications.internal.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

/** One browser that allows PlayChale's notifications, and where to send them. */
@Entity
@Table(name = "push_subscriptions")
public class PushSubscription {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	private UUID userId;

	private String endpoint;

	private String p256dh;

	private String auth;

	private Instant createdAt;

	private Instant lastSentAt;

	protected PushSubscription() {
	}

	public PushSubscription(UUID userId, String endpoint, String p256dh, String auth, Instant now) {
		this.userId = userId;
		this.endpoint = endpoint;
		this.p256dh = p256dh;
		this.auth = auth;
		this.createdAt = now;
	}

	/** The same browser again, maybe for someone else now, maybe with new keys. */
	public void renew(UUID userId, String p256dh, String auth) {
		this.userId = userId;
		this.p256dh = p256dh;
		this.auth = auth;
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

	public String getEndpoint() {
		return endpoint;
	}

	public String getP256dh() {
		return p256dh;
	}

	public String getAuth() {
		return auth;
	}

}
