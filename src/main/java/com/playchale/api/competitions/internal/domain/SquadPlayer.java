package com.playchale.api.competitions.internal.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Embeddable;

/** One player in a team's squad for a league: a row of entry_players. */
@Embeddable
public record SquadPlayer(UUID userId, Instant addedAt) {
}
