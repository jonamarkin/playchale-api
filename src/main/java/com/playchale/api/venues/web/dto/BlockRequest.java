package com.playchale.api.venues.web.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** The web app's BlockInput. */
public record BlockRequest(@NotNull(message = "Pick one of your pitches.") UUID pitchId,
		@NotNull(message = "Pick a start time.") Instant startsAt, @NotNull(message = "Pick an end time.") Instant endsAt,
		String note) {
}
