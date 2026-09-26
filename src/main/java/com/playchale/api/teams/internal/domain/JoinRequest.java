package com.playchale.api.teams.internal.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

/** Someone asking a team's captain for a place. */
@Entity
@Table(name = "team_join_requests")
public class JoinRequest {

	public static final String PENDING = "pending";

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	private UUID teamId;

	private UUID userId;

	private String status;

	private Instant createdAt;

	private Instant answeredAt;

	protected JoinRequest() {
	}

	public JoinRequest(UUID teamId, UUID userId, Instant now) {
		this.teamId = teamId;
		this.userId = userId;
		this.status = PENDING;
		this.createdAt = now;
	}

	public void answer(boolean accept, Instant now) {
		status = accept ? "accepted" : "declined";
		answeredAt = now;
	}

	public boolean isPending() {
		return PENDING.equals(status);
	}

	public UUID getId() {
		return id;
	}

	public UUID getTeamId() {
		return teamId;
	}

	public UUID getUserId() {
		return userId;
	}

	public String getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
