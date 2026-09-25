package com.playchale.api.venues.internal.service;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One bookable hour on one pitch, as the web app's Slot type.
 *
 * @param reason why it isn't available: "booked", "blocked" or "past"
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SlotResponse(UUID pitchId, Instant startsAt, Instant endsAt, long price, boolean available, String reason) {
}
