package com.playchale.api.integration.payments;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Which {@link PaymentProvider} runs. Paystack's adapter will be added here. */
@Configuration
class PaymentsConfig {

	@Bean
	@Profile("dev")
	PaymentProvider simulatedPaymentProvider(Clock clock) {
		return new SimulatedPaymentProvider(clock);
	}

	/** Everywhere else there's no real provider yet, so the app refuses to start rather than pretend to take money. */
	@Bean
	@Profile("!dev")
	PaymentProvider noPaymentProvider() {
		throw new IllegalStateException("No payment provider is configured yet, so the app can't run outside the dev profile");
	}

}
