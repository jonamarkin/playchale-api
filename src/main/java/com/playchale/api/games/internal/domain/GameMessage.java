package com.playchale.api.games.internal.domain;

import java.time.Instant;
import java.util.UUID;

import com.playchale.api.shared.error.BusinessException;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

/**
 * Something one of the players said about a game. Only the people in that game can read or write it,
 * which is the moderation model as much as the privacy one: the audience is the ten who turn up, and
 * the host can take anything down.
 *
 * <p>Not part of the {@link Game} aggregate: a game is loaded on every view of it and the talk is
 * not wanted most of those times.
 */
@Entity
@Table(name = "game_messages")
public class GameMessage {

	/** Long enough to say what is going on, short enough not to be a post. */
	public static final int MAX_LENGTH = 500;

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	private UUID gameId;

	private UUID userId;

	private String body;

	private Instant createdAt;

	protected GameMessage() {
	}

	public GameMessage(UUID gameId, UUID userId, String body, Instant now) {
		this.gameId = gameId;
		this.userId = userId;
		this.body = clean(body);
		this.createdAt = now;
	}

	private static String clean(String body) {
		var trimmed = body == null ? "" : body.strip();
		if (trimmed.isEmpty()) {
			throw BusinessException.invalid("Say something first.");
		}
		if (trimmed.length() > MAX_LENGTH) {
			throw BusinessException.invalid("That's longer than %d characters. Keep it short.".formatted(MAX_LENGTH));
		}
		return trimmed;
	}

	/** Whose it is to take down: the one who said it, or the host of the game. */
	public boolean canBeRemovedBy(UUID userId, UUID hostId) {
		return this.userId.equals(userId) || hostId.equals(userId);
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

	public String getBody() {
		return body;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
