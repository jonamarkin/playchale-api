package com.playchale.api.integration.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Which {@link SmsSender} runs. On a laptop, texts go to the log. The real provider's adapter will be
 * added here; until then there's none outside the dev profile, and signing in by phone isn't offered there.
 */
@Configuration
class SmsConfig {

	private static final Logger log = LoggerFactory.getLogger("sms");

	/** On a laptop, texts go to the log instead of a phone. */
	@Bean
	@Profile("dev")
	SmsSender loggingSmsSender() {
		return (phone, message) -> log.info("SMS (dev profile: not sent) to={} message={}", phone, message);
	}

}
