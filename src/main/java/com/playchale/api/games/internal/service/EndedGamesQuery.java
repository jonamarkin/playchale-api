package com.playchale.api.games.internal.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.playchale.api.games.api.EndedGames;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Played games still waiting for a score, read straight from the table: a result sets the status to completed. */
@Component
class EndedGamesQuery implements EndedGames {

	private final JdbcClient jdbc;

	EndedGamesQuery(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	private record Row(UUID id, String title, UUID hostId, OffsetDateTime startsAt, OffsetDateTime endsAt, String timezone) {
	}

	@Override
	@Transactional(readOnly = true)
	public List<Ended> endedBetween(Instant from, Instant to) {
		return jdbc.sql("""
				SELECT id, title, host_id, starts_at, starts_at + make_interval(mins => duration_minutes) AS ends_at, timezone
				FROM games
				WHERE status IN ('open', 'full') AND competition_id IS NULL
				  AND starts_at + make_interval(mins => duration_minutes) >= :from
				  AND starts_at + make_interval(mins => duration_minutes) < :to
				ORDER BY ends_at
				""").param("from", from.atOffset(ZoneOffset.UTC)).param("to", to.atOffset(ZoneOffset.UTC)).query(Row.class).list().stream()
			.map(r -> new Ended(r.id(), r.title(), r.hostId(), r.startsAt().toInstant(), r.endsAt().toInstant(), r.timezone()))
			.toList();
	}

}
