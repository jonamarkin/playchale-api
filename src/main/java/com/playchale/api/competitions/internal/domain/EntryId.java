package com.playchale.api.competitions.internal.domain;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Embeddable;

/** A team in a league: the league and the team together. */
@Embeddable
public record EntryId(UUID competitionId, UUID teamId) implements Serializable {
}
