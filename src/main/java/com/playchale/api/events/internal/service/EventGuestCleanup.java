package com.playchale.api.events.internal.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;

import com.playchale.api.shared.scheduling.ClusterLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Forgets the numbers of people who signed up to a games day as guests, 90 days after it ended.
 * Until then they're how the organisers reach someone and how the place becomes theirs when they
 * sign up; after that, holding them has no use. The name stays, so the results still read right.
 * Daily, on one copy of the API at a time.
 */
@Component
public class EventGuestCleanup {

	static final Duration KEPT_FOR = Duration.ofDays(90);

	private static final Logger log = LoggerFactory.getLogger(EventGuestCleanup.class);

	private final JdbcClient jdbc;

	private final ClusterLock lock;

	private final Clock clock;

	EventGuestCleanup(JdbcClient jdbc, ClusterLock lock, Clock clock) {
		this.jdbc = jdbc;
		this.lock = lock;
		this.clock = clock;
	}

	@Scheduled(initialDelayString = "PT15M", fixedDelayString = "PT24H")
	@Transactional
	public void tidy() {
		if (!lock.tryLock("event-guest-cleanup")) {
			return;
		}
		int forgotten = jdbc.sql("""
				UPDATE event_people p SET guest_phone = NULL
				FROM events e
				WHERE e.id = p.event_id AND p.user_id IS NULL AND p.guest_phone IS NOT NULL AND e.ends_on < :before
				""").param("before", LocalDate.ofInstant(clock.instant().minus(KEPT_FOR), ZoneOffset.UTC)).update();
		if (forgotten > 0) {
			log.info("Forgot the numbers of {} games-day guests whose events were over 90 days ago", forgotten);
		}
	}

}
