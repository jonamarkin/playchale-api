package com.playchale.api.competitions.internal.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Embeddable;

/** One player in a squad. The competition is kept with them so the database allows one team per player per league. */
@Embeddable
public record SquadMember(UUID userId, UUID competitionId, Instant addedAt) {
}
