package com.playchale.api.integration.payments;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Which {@link PaymentProvider} runs: Paystack whenever its secret key is set; otherwise, on a
 * laptop (the dev profile), the simulated one. Anywhere else without a key there's none, which is
 * fine while players pay hosts directly (the payments module checks that at startup).
 */
@Configuration
@EnableConfigurationProperties(PaystackProperties.class)
class PaymentsConfig {

	/** Paystack gets a few seconds to answer, so a slow day there can't tie up the API's threads. */
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

	private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

	@Bean
	@ConditionalOnExpression("!'${playchale.paystack.secret-key:}'.isBlank()")
	PaymentProvider paystackPaymentProvider(PaystackProperties paystack, ObjectMapper json, Clock clock) {
		var requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
		requests.setReadTimeout(READ_TIMEOUT);
		return new PaystackPaymentProvider(RestClient.builder().requestFactory(requests), paystack, json, clock);
	}

	@Bean
	@Profile("dev")
	@ConditionalOnMissingBean(PaymentProvider.class)
	PaymentProvider simulatedPaymentProvider(Clock clock) {
		return new SimulatedPaymentProvider(clock);
	}

}
