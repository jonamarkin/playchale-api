package com.playchale.api.users.internal.service;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.UUID;

import com.playchale.api.shared.scheduling.ClusterLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retires guest hosts nobody needs any more: one that never hosted, two days on; and one whose
 * last game was 90 days ago, with nothing repeating. Through ordinary account deletion, so their
 * number and everything else personal goes, and their games still add up. Daily, on one copy of
 * the API at a time (the lock lasts the job's transaction; each guest is retired in its own, so one
 * that can't be doesn't keep the rest).
 *
 * <p>It reads the games tables directly, as the admin desk does: "has this account hosted, and
 * when last" is all it asks, and a module method for one daily question isn't worth it.
 */
@Component
class GuestHostCleanup {

	static final Duration UNUSED_FOR = Duration.ofDays(2);

	static final Duration KEPT_AFTER_LAST_GAME = Duration.ofDays(90);

	private static final Logger log = LoggerFactory.getLogger(GuestHostCleanup.class);

	private final JdbcClient jdbc;

	private final ClusterLock lock;

	private final Clock clock;

	private final UserProfileService profiles;

	private final TransactionTemplate eachOnItsOwn;

	GuestHostCleanup(JdbcClient jdbc, ClusterLock lock, Clock clock, UserProfileService profiles, TransactionTemplate tx) {
		this.jdbc = jdbc;
		this.lock = lock;
		this.clock = clock;
		this.profiles = profiles;
		this.eachOnItsOwn = new TransactionTemplate(tx.getTransactionManager());
		this.eachOnItsOwn.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@Scheduled(initialDelayString = "PT20M", fixedDelayString = "PT24H")
	@Transactional
	public void tidy() {
		if (!lock.tryLock("guest-host-cleanup")) {
			return;
		}
		var now = clock.instant();
		var done = jdbc.sql("""
				SELECT u.id FROM users u
				WHERE u.guest AND u.deleted_at IS NULL
				  AND NOT EXISTS (SELECT 1 FROM game_series s WHERE s.host_id = u.id AND s.status = 'active')
				  AND (
				    (u.created_at < :unused AND NOT EXISTS (SELECT 1 FROM games g WHERE g.host_id = u.id))
				    OR (EXISTS (SELECT 1 FROM games g WHERE g.host_id = u.id)
				        AND NOT EXISTS (SELECT 1 FROM games g WHERE g.host_id = u.id AND g.starts_at >= :kept))
				  )
				""").param("unused", now.minus(UNUSED_FOR).atOffset(ZoneOffset.UTC))
			.param("kept", now.minus(KEPT_AFTER_LAST_GAME).atOffset(ZoneOffset.UTC))
			.query(UUID.class).list();
		int retired = 0;
		for (var guest : done) {
			try {
				eachOnItsOwn.executeWithoutResult(status -> profiles.deleteAccount(guest));
				retired++;
			}
			catch (RuntimeException kept) {
				log.warn("Kept guest host {}: {}", guest, kept.getMessage());
			}
		}
		if (retired > 0) {
			log.info("Retired {} guest host(s) nobody needs any more", retired);
		}
	}

}
