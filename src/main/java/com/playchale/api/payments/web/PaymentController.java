package com.playchale.api.payments.web;

import java.util.List;
import java.util.UUID;

import com.playchale.api.payments.internal.service.MovementResponse;
import com.playchale.api.payments.internal.service.PaymentResponse;
import com.playchale.api.payments.internal.service.PaymentService;
import com.playchale.api.payments.web.dto.StartPaymentRequest;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.shared.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Payments, one endpoint per method of {@code payments} in the web app's contract. */
@RestController
class PaymentController {

	private final PaymentService payments;

	PaymentController(PaymentService payments) {
		this.payments = payments;
	}

	/** payments.start */
	@PostMapping("/payments")
	@ResponseStatus(HttpStatus.CREATED)
	PaymentResponse start(CurrentUser me, @Valid @RequestBody StartPaymentRequest request) {
		return payments.start(request.gameId(), request.method(), request.payerPhone(), me.id());
	}

	/** payments.status: asks the provider while it's pending. The web app polls this. */
	@GetMapping("/payments/{id}/status")
	PaymentResponse status(CurrentUser me, @PathVariable UUID id) {
		return payments.status(id, me.id());
	}

	/** payments.get: 404 when it isn't yours or doesn't exist, which the web app reads as null. */
	@GetMapping("/payments/{id}")
	PaymentResponse get(CurrentUser me, @PathVariable UUID id) {
		return payments.get(id, me.id()).orElseThrow(() -> BusinessException.notFound("We couldn’t find that payment."));
	}

	/** payments.statement */
	@GetMapping("/me/statement")
	List<MovementResponse> statement(CurrentUser me) {
		return payments.statement(me.id());
	}

}
