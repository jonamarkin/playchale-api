package com.playchale.api.venues.internal.domain;

import java.util.UUID;

/** A pitch as the owner describes it. {@code id} is set for pitches that already exist. */
public record PitchDetails(UUID id, String name, String sport, String format, String surface, long pricePerHour) {
}
