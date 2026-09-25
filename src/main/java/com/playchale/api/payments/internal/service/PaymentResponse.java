package com.playchale.api.payments.internal.service;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.playchale.api.payments.internal.domain.Payment;

/** A payment as the web app's Payment type. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentResponse(UUID id, UUID gameId, UUID userId, String method, long amount, long fee, String status, String reference,
		String payerPhone, Instant createdAt, String failureReason) {

	static PaymentResponse of(Payment p) {
		return new PaymentResponse(p.getId(), p.getGameId(), p.getUserId(), p.getMethod(), p.getAmount(), p.getFee(), p.getStatus(),
				p.getReference(), p.getPayerPhone(), p.getCreatedAt(), p.getFailureReason());
	}

}
