package com.playchale.api.integration.payments;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Which {@link PaymentProvider} runs: Paystack whenever its secret key is set; otherwise, on a
 * laptop (the dev profile), the simulated one. Anywhere else without a key the app refuses to start,
 * rather than pretend to take money.
 */
@Configuration
@EnableConfigurationProperties(PaystackProperties.class)
class PaymentsConfig {

	/** Paystack gets a few seconds to answer, so a slow day there can't tie up the API's threads. */
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

	private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

	@Bean
	PaymentProvider paymentProvider(PaystackProperties paystack, Environment environment, ObjectMapper json, Clock clock) {
		if (paystack.configured()) {
			var requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
			requests.setReadTimeout(READ_TIMEOUT);
			return new PaystackPaymentProvider(RestClient.builder().requestFactory(requests), paystack, json, clock);
		}
		if (environment.acceptsProfiles(Profiles.of("dev"))) {
			return new SimulatedPaymentProvider(clock);
		}
		throw new IllegalStateException("No payment provider is configured: set PLAYCHALE_PAYSTACK_SECRET_KEY");
	}

}
