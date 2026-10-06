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
	 * @param destination where the money is to land: the payee's own account with the provider, as the
	 *                    provider refers to it. Never null — a payment with nowhere of its own to land
	 *                    is refused before it gets here, rather than collected into ours.
	 */
	record Charge(String reference, String method, long amount, String currency, String payerPhone, String email, String returnUrl,
			String destination) {
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

	/**
	 * Whether this provider settles a payment straight to the payee, so the money is never ours even
	 * for a moment.
	 *
	 * <p>This is the difference between taking payments and running a payment service. Collecting
	 * into PlayChale's own balance and paying people out afterwards would make us the holder of
	 * everyone's money — a thing that needs a licence from the Bank of Ghana, that our own screens
	 * and films tell players we don't do, and that puts what someone is owed at the mercy of our
	 * account rather than theirs. A provider that can't do this can't be used to collect in the app.
	 */
	boolean settlesToHost();

	/**
	 * Where money is to be paid, registered with the provider once per payee.
	 *
	 * <p>A payee is whoever is actually owed and can hold an account in their own name: a partner
	 * venue being paid for a pitch, say. It is deliberately not the ordinary host of a kickabout —
	 * asking someone collecting for a Saturday game to register a settlement account is both more
	 * than they will do and more than we should be arranging on their behalf. They are paid
	 * directly, outside the app, which is why {@code inApp} can be off and everything still works.
	 */
	record Payee(String name, String bankCode, String accountNumber, String country) {
	}

	/**
	 * Registers where a payee wants their money, and returns the provider's reference for it, to be
	 * passed back as a charge's {@code destination}. Empty when this provider can't settle to payees.
	 */
	default Optional<String> registerPayee(Payee payee) {
		return Optional.empty();
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
