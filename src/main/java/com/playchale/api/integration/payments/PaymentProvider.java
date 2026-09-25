package com.playchale.api.integration.payments;

import java.time.Instant;

/**
 * A payment provider: mobile money (MTN, Telecel, AirtelTigo) and cards. The adapter is the only
 * class that knows the provider's API. PlayChale never holds the money: it moves from the payer to
 * the host through the provider.
 */
public interface PaymentProvider {

	/**
	 * Asks the provider to collect a payment. Mobile money is approved on the payer's phone, so it
	 * starts out pending.
	 *
	 * @param reference our reference for it, unique per payment, so asking twice can't charge twice
	 */
	record Charge(String reference, String method, long amount, String currency, String payerPhone) {
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

	void charge(Charge charge);

	/**
	 * Asks the provider where a charge has got to.
	 *
	 * @param startedAt when it was started, for providers that need it
	 */
	Status check(Charge charge, Instant startedAt);

}
