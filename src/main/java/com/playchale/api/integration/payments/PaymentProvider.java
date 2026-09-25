package com.playchale.api.integration.payments;

import java.time.Instant;
import java.util.Optional;

/**
 * A payment provider: mobile money (MTN, Telecel, AirtelTigo) and cards. The adapter is the only
 * class that knows the provider's API. PlayChale never holds the money: it moves from the payer to
 * the host through the provider.
 */
public interface PaymentProvider {

	/**
	 * Asks the provider to collect a payment.
	 *
	 * @param reference   ours, unique per payment, so asking twice can't charge twice
	 * @param method      "momo-mtn", "momo-telecel", "momo-at" or "card"
	 * @param amount      in minor units of {@code currency}
	 * @param payerPhone  the mobile money number, for providers that charge it directly; may be null
	 * @param email       where the provider sends its receipt; may be null for providers that don't need one
	 * @param returnUrl   where a hosted checkout sends the payer back to when they're done
	 */
	record Charge(String reference, String method, long amount, String currency, String payerPhone, String email, String returnUrl) {
	}

	/** @param checkoutUrl the provider's page to send the payer to, or null when they approve it on their phone */
	record Started(String checkoutUrl) {
	}

	/** Where a charge has got to, as the provider tells it. */
	record Status(State state, String failureReason) {

		public static Status pending() {
			return new Status(State.PENDING, null);
		}

		public static Status succeeded() {
			return new Status(State.SUCCEEDED, null);
		}

		public static Status failed(String reason) {
			return new Status(State.FAILED, reason);
		}

	}

	enum State {

		PENDING, SUCCEEDED, FAILED

	}

	/** Whether the payer has to give the mobile money number to charge before starting. */
	boolean needsPayerPhone();

	/** Whether the payer needs an email address for the provider's receipt. */
	boolean needsEmail();

	Started charge(Charge charge);

	/**
	 * Asks the provider where a charge has got to. The only source of truth for whether money moved.
	 *
	 * @param startedAt when it was started, for providers that need it
	 */
	Status check(Charge charge, Instant startedAt);

	/**
	 * If a webhook really came from this provider (its signature checks out) and is about a charge,
	 * the charge's reference. It's only ever a prompt to {@link #check} that charge.
	 */
	default Optional<String> webhookReference(String body, String signature) {
		return Optional.empty();
	}

}
