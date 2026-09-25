package com.playchale.api.games.internal.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Embeddable;

/** A player saying the result is right. */
@Embeddable
public record Confirmation(UUID userId, Instant confirmedAt) {
}
