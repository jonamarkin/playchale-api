package com.playchale.api.integration.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Which {@link EmailSender} runs. On a laptop, emails go to the log. The real provider's adapter
 * will be added here; until then there's none outside the dev profile, and signing in by email
 * isn't offered there.
 */
@Configuration
class EmailConfig {

	private static final Logger log = LoggerFactory.getLogger("email");

	@Bean
	@Profile("dev")
	EmailSender loggingEmailSender() {
		return (to, subject, text) -> log.info("Email (dev profile: not sent) to={} subject={} text={}", to, subject, text);
	}

}
