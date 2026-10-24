package com.playchale.api.auth.internal.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import com.playchale.api.auth.api.Presence;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** A session's last_seen_at moves (to the hour) on every request, so it says when someone was last here. */
@Component
class SessionPresence implements Presence {

	private final JdbcClient jdbc;

	SessionPresence(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public Set<UUID> seenSince(Instant since) {
		return Set.copyOf(jdbc.sql("SELECT DISTINCT user_id FROM sessions WHERE last_seen_at >= :since")
			.param("since", since.atOffset(ZoneOffset.UTC)).query(UUID.class).list());
	}

}
