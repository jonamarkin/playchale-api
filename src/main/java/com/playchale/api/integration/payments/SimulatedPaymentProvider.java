package com.playchale.api.integration.payments;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * A stand-in for the real provider on a laptop and in tests, behaving like the web app's mock
 * (webapp/app/services/mock/payments.ts): a charge is pending while the payer "approves it on their
 * phone", then succeeds a few seconds later. A mobile money number ending in 000 is declined, so the
 * failure path can be tried.
 */
class SimulatedPaymentProvider implements PaymentProvider {

	static final Duration SETTLES_AFTER = Duration.ofMillis(2600);

	private final Clock clock;

	SimulatedPaymentProvider(Clock clock) {
		this.clock = clock;
	}

	/** The payer "approves it on their phone", so the number matters: one ending in 000 is declined. */
	@Override
	public boolean needsPayerPhone() {
		return true;
	}

	@Override
	public boolean needsEmail() {
		return false;
	}

	@Override
	public Started charge(Charge charge) {
		// Nothing to send anywhere: the answer is worked out when it's checked.
		return new Started(null);
	}

	@Override
	public Status check(Charge charge, Instant startedAt) {
		if (clock.instant().isBefore(startedAt.plus(SETTLES_AFTER))) {
			return Status.pending();
		}
		if (charge.payerPhone() != null && charge.payerPhone().endsWith("000")) {
			return Status.failed("The payment was declined on the phone. No money was taken.");
		}
		return Status.succeeded();
	}

}
