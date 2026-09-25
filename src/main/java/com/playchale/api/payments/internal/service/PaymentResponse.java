package com.playchale.api.payments.internal.service;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.playchale.api.payments.internal.domain.Payment;

/**
 * A payment as the web app's Payment type. {@code authorizationUrl} is the provider's checkout page to
 * send the payer to, while there's one and the payment is pending.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentResponse(UUID id, UUID gameId, UUID userId, String method, long amount, long fee, String status, String reference,
		String payerPhone, Instant createdAt, String failureReason, String authorizationUrl) {

	static PaymentResponse of(Payment p) {
		return new PaymentResponse(p.getId(), p.getGameId(), p.getUserId(), p.getMethod(), p.getAmount(), p.getFee(), p.getStatus(),
				p.getReference(), p.getPayerPhone(), p.getCreatedAt(), p.getFailureReason(), p.isPending() ? p.getCheckoutUrl() : null);
	}

}
