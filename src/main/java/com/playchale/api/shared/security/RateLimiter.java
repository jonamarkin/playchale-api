package com.playchale.api.shared.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.playchale.api.shared.scheduling.ClusterLock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counts things per key in fixed time windows (sign-in texts per connection per hour, say), in
 * Postgres so the limit holds across every copy of the API. One atomic upsert per attempt, so two
 * requests at the same moment can't both slip under the limit.
 */
@Component
public class RateLimiter {

	private final JdbcClient jdbc;

	private final ClusterLock lock;

	private final Clock clock;

	RateLimiter(JdbcClient jdbc, ClusterLock lock, Clock clock) {
		this.jdbc = jdbc;
		this.lock = lock;
		this.clock = clock;
	}

	/**
	 * Counts one more attempt for the key in the current window, and says whether it's still within
	 * the limit. Counted in its own transaction, so a refusal later in the request doesn't undo it.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean tryAcquire(String key, Duration window, int limit) {
		var count = jdbc.sql("""
				INSERT INTO rate_limits (key, window_start, count) VALUES (:key, :window, 1)
				ON CONFLICT (key, window_start) DO UPDATE SET count = rate_limits.count + 1
				RETURNING count
				""")
			.param("key", key)
			.param("window", windowStart(clock.instant(), window).atOffset(ZoneOffset.UTC))
			.query(Integer.class)
			.single();
		return count <= limit;
	}

	/** Forgets windows that ended more than a day ago. Hourly, on one copy of the API at a time. */
	@Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT1H")
	@Transactional
	public void forgetOldWindows() {
		if (lock.tryLock("rate-limits-cleanup")) {
			jdbc.sql("DELETE FROM rate_limits WHERE window_start < :before")
				.param("before", clock.instant().minus(Duration.ofDays(1)).atOffset(ZoneOffset.UTC))
				.update();
		}
	}

	private static Instant windowStart(Instant now, Duration window) {
		long size = window.toSeconds();
		return Instant.ofEpochSecond(now.getEpochSecond() / size * size);
	}

}
