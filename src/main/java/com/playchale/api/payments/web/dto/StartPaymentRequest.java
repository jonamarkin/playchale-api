package com.playchale.api.payments.web.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** {"gameId", "method", "payerPhone"?} */
public record StartPaymentRequest(@NotNull(message = "Pick the game you’re paying for.") UUID gameId,
		@NotNull(message = "Pick how you’ll pay.") String method, String payerPhone) {
}
