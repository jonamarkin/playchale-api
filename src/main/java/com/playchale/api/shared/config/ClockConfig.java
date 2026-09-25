package com.playchale.api.shared.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The clock the API reads the time from. It's a bean so tests can swap in one they control, and
 * check that codes expire without waiting ten minutes.
 */
@Configuration
class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
