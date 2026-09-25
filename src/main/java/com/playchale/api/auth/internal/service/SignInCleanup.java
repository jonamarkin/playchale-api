package com.playchale.api.auth.internal.service;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

import com.playchale.api.shared.scheduling.ClusterLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tidies away what sign-in leaves behind: codes a day after they were sent (the hourly limit only
 * needs the last hour), and sessions a week after they ended. Hourly, on one copy of the API at a time.
 */
@Component
class SignInCleanup {

	private static final Logger log = LoggerFactory.getLogger(SignInCleanup.class);

	private final JdbcClient jdbc;

	private final ClusterLock lock;

	private final Clock clock;

	SignInCleanup(JdbcClient jdbc, ClusterLock lock, Clock clock) {
		this.jdbc = jdbc;
		this.lock = lock;
		this.clock = clock;
	}

	@Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT1H")
	@Transactional
	public void tidy() {
		if (!lock.tryLock("sign-in-cleanup")) {
			return;
		}
		var now = clock.instant();
		int codes = jdbc.sql("DELETE FROM sign_in_codes WHERE created_at < :before")
			.param("before", now.minus(Duration.ofDays(1)).atOffset(ZoneOffset.UTC)).update();
		int sessions = jdbc.sql("DELETE FROM sessions WHERE expires_at < :before")
			.param("before", now.minus(Duration.ofDays(7)).atOffset(ZoneOffset.UTC)).update();
		if (codes + sessions > 0) {
			log.info("Tidied {} old sign-in codes and {} ended sessions", codes, sessions);
		}
	}

}
