package com.playchale.api.payments.web;

import com.playchale.api.payments.internal.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Paystack tells us here when something happens to a payment (set this address in the Paystack
 * dashboard). The body is read exactly as sent, because the signature is over those bytes.
 */
@RestController
class PaystackWebhookController {

	private final PaymentService payments;

	PaystackWebhookController(PaymentService payments) {
		this.payments = payments;
	}

	/** 200 for a genuine webhook (Paystack stops retrying), 401 for anything else. */
	@PostMapping("/webhooks/paystack")
	ResponseEntity<Void> receive(@RequestBody String body, @RequestHeader(name = "x-paystack-signature", required = false) String signature) {
		return payments.handleWebhook("paystack", body, signature) ? ResponseEntity.ok().build() : ResponseEntity.status(401).build();
	}

}
