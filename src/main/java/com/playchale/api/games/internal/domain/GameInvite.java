package com.playchale.api.games.internal.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * The host asking someone to play, and their answer. Accepting is joining the game; declining is
 * recorded so the host knows. Inviting again (a nudge, or asking someone who said no) asks afresh.
 */
@Entity
@Table(name = "game_invites")
public class GameInvite {

	public static final String PENDING = "pending";

	public static final String ACCEPTED = "accepted";

	public static final String DECLINED = "declined";

	@EmbeddedId
	private InviteId id;

	private UUID teamId;

	private UUID invitedBy;

	private String status;

	private Instant invitedAt;

	private Instant answeredAt;

	protected GameInvite() {
	}

	public GameInvite(UUID gameId, UUID userId, UUID teamId, UUID invitedBy, Instant now) {
		this.id = new InviteId(gameId, userId);
		ask(teamId, invitedBy, now);
	}

	/** Asked (again): waiting for their answer. A team invite keeps the team it came with. */
	public void ask(UUID teamId, UUID invitedBy, Instant now) {
		if (teamId != null) {
			this.teamId = teamId;
		}
		this.invitedBy = invitedBy;
		this.status = PENDING;
		this.invitedAt = now;
		this.answeredAt = null;
	}

	public void accept(Instant now) {
		this.status = ACCEPTED;
		this.answeredAt = now;
	}

	public void decline(Instant now) {
		this.status = DECLINED;
		this.answeredAt = now;
	}

	public boolean isPending() {
		return PENDING.equals(status);
	}

	public boolean isAccepted() {
		return ACCEPTED.equals(status);
	}

	public UUID getGameId() {
		return id.gameId();
	}

	public UUID getUserId() {
		return id.userId();
	}

	public UUID getTeamId() {
		return teamId;
	}

	public UUID getInvitedBy() {
		return invitedBy;
	}

	public String getStatus() {
		return status;
	}

	public Instant getInvitedAt() {
		return invitedAt;
	}

	public Instant getAnsweredAt() {
		return answeredAt;
	}

}
