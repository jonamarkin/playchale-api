package com.playchale.api.games.internal.service;

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
 * Forgets guests' numbers and emails 90 days after their game. Until then they're how the host
 * reaches a guest and how the spot (with its result) becomes theirs when they sign up; after that a
 * guest who never signed up has no further use for us holding them. The name stays, so the game's
 * roster still reads right. Daily, on one copy of the API at a time.
 */
@Component
class GuestDetailsCleanup {

	static final Duration KEPT_FOR = Duration.ofDays(90);

	private static final Logger log = LoggerFactory.getLogger(GuestDetailsCleanup.class);

	private final JdbcClient jdbc;

	private final ClusterLock lock;

	private final Clock clock;

	GuestDetailsCleanup(JdbcClient jdbc, ClusterLock lock, Clock clock) {
		this.jdbc = jdbc;
		this.lock = lock;
		this.clock = clock;
	}

	@Scheduled(initialDelayString = "PT10M", fixedDelayString = "PT24H")
	@Transactional
	public void tidy() {
		if (!lock.tryLock("guest-details-cleanup")) {
			return;
		}
		int forgotten = jdbc.sql("""
				UPDATE game_participants p SET guest_phone = NULL, guest_email = NULL
				FROM games g
				WHERE g.id = p.game_id AND p.user_id IS NULL AND (p.guest_phone IS NOT NULL OR p.guest_email IS NOT NULL)
				  AND g.starts_at < :before
				""").param("before", clock.instant().minus(KEPT_FOR).atOffset(ZoneOffset.UTC)).update();
		if (forgotten > 0) {
			log.info("Forgot the contact details of {} guests whose games were over 90 days ago", forgotten);
		}
	}

}
