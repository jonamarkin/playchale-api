package com.playchale.api.notifications.internal.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

/** Something a player should know about, shown in the app's notification list. */
@Entity
@Table(name = "notifications")
public class Notification {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	private UUID userId;

	/** One of the web app's NotificationKind values, e.g. "player-joined". */
	private String kind;

	private String title;

	private String body;

	/** In-app path to open, e.g. /games/<id>?pay=1. */
	private String link;

	private UUID actorId;

	private Instant createdAt;

	private Instant readAt;

	protected Notification() {
	}

	public Notification(UUID userId, String kind, String title, String body, String link, UUID actorId, Instant now) {
		this.userId = userId;
		this.kind = kind;
		this.title = title;
		this.body = body;
		this.link = link;
		this.actorId = actorId;
		this.createdAt = now;
	}

	public UUID getId() {
		return id;
	}

	public UUID getUserId() {
		return userId;
	}

	public String getKind() {
		return kind;
	}

	public String getTitle() {
		return title;
	}

	public String getBody() {
		return body;
	}

	public String getLink() {
		return link;
	}

	public UUID getActorId() {
		return actorId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public boolean isRead() {
		return readAt != null;
	}

}
