package com.playchale.api.integration.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Which {@link SmsSender} runs. The real provider's adapter will be added here. */
@Configuration
class SmsConfig {

	private static final Logger log = LoggerFactory.getLogger("sms");

	/** On a laptop, texts go to the log instead of a phone. */
	@Bean
	@Profile("dev")
	SmsSender loggingSmsSender() {
		return (phone, message) -> log.info("SMS (dev profile: not sent) to={} message={}", phone, message);
	}

	/**
	 * Everywhere else there's no SMS provider yet, so the app refuses to start rather than quietly
	 * writing sign-in codes into production logs.
	 */
	@Bean
	@Profile("!dev")
	SmsSender noSmsSender() {
		throw new IllegalStateException("No SMS provider is configured yet, so the app can't run outside the dev profile");
	}

}
