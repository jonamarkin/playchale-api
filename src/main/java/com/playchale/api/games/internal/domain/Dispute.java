package com.playchale.api.games.internal.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Embeddable;

/** A player saying the result isn't right, in their own words. */
@Embeddable
public record Dispute(UUID userId, String reason, Instant disputedAt) {
}
