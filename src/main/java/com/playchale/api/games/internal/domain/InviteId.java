package com.playchale.api.games.internal.domain;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Embeddable;

/** One player's invite to one game. */
@Embeddable
public record InviteId(UUID gameId, UUID userId) implements Serializable {
}
