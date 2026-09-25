package com.playchale.api.payments.internal.service;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.playchale.api.users.api.UserSummary;

/** A statement line as the web app's MovementView: the movement, with the other person and the game named. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MovementResponse(UUID id, UUID userId, String direction, String kind, long amount, String currency, UUID gameId,
		UUID counterpartyId, String method, String reference, String status, Instant createdAt, UserSummary counterparty,
		String gameTitle) {
}
