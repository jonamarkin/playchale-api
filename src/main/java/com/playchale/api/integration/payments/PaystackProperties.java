package com.playchale.api.integration.payments;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Paystack's settings. Set PLAYCHALE_PAYSTACK_SECRET_KEY (sk_test_... to try it, sk_live_... for
 * real money) and payments go through Paystack in any profile; leave it empty and a laptop uses the
 * simulated provider.
 */
@ConfigurationProperties("playchale.paystack")
public record PaystackProperties(String secretKey, @DefaultValue("https://api.paystack.co") String baseUrl) {

	public boolean configured() {
		return secretKey != null && !secretKey.isBlank();
	}

}
