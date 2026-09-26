package com.playchale.api.teams.internal.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Embeddable;

/** Someone in a team: a row of team_members. */
@Embeddable
public record TeamMember(UUID userId, Instant joinedAt) {
}
