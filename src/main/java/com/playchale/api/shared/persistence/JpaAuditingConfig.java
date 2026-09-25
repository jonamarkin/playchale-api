package com.playchale.api.shared.persistence;

import java.time.Clock;
import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** Turns on created/updated timestamps for {@link AuditableEntity}, read from the app's Clock. */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingTime")
class JpaAuditingConfig {

	@Bean
	DateTimeProvider auditingTime(Clock clock) {
		return () -> Optional.of(clock.instant());
	}

}
