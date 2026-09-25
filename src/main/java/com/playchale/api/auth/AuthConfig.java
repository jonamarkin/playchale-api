package com.playchale.api.auth;

import com.playchale.api.config.PlaychaleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AuthConfig {

	private static final Logger log = LoggerFactory.getLogger("sign-in");

	/**
	 * Development "texts" codes to the log. Production has no SMS provider yet, so the app refuses
	 * to start rather than quietly writing sign-in codes into production logs.
	 */
	@Bean
	CodeSender codeSender(PlaychaleProperties properties) {
		if (properties.isProduction()) {
			throw new IllegalStateException("No SMS provider is configured yet, so sign-in can't run in production");
		}
		return (phone, code) -> log.info("sign-in code (development: not texted) phone={} code={}", phone, code);
	}

}
