package com.playchale.api.teams.api;

import java.time.Instant;
import java.util.UUID;

/** Someone asking a team's captain for a place. */
public record JoinRequestCard(UUID id, UUID teamId, UUID userId, String status, Instant createdAt) {
}
