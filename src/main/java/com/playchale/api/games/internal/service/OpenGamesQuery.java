package com.playchale.api.games.internal.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.playchale.api.games.api.OpenGames;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** OpenGames, read straight from the games tables: two queries whatever the number of games. */
@Component
class OpenGamesQuery implements OpenGames {

	private final JdbcClient jdbc;

	OpenGamesQuery(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	private record Row(UUID id, String title, String sport, String format, String venueName, String venueArea, String country,
			OffsetDateTime startsAt, String timezone, Integer capacity, long players, UUID hostId) {
	}

	@Override
	@Transactional(readOnly = true)
	public List<OpenGame> between(Instant from, Instant to) {
		var rows = jdbc.sql("""
				SELECT g.id, g.title, g.sport, g.format, g.venue_name, g.venue_area, g.country, g.starts_at, g.timezone, g.capacity,
				       (SELECT count(*) FROM game_participants p WHERE p.game_id = g.id) AS players, g.host_id
				FROM games g
				WHERE g.visibility = 'public' AND g.status = 'open' AND g.competition_id IS NULL
				  AND g.starts_at >= :from AND g.starts_at < :to
				ORDER BY g.starts_at
				""").param("from", from.atOffset(ZoneOffset.UTC)).param("to", to.atOffset(ZoneOffset.UTC)).query(Row.class).list()
			.stream().filter(r -> r.capacity() == null || r.players() < r.capacity()).toList();
		var players = new HashMap<UUID, Set<UUID>>();
		if (!rows.isEmpty()) {
			jdbc.sql("SELECT game_id, user_id FROM game_participants WHERE user_id IS NOT NULL AND game_id IN (:ids)")
				.param("ids", rows.stream().map(Row::id).toList()).query().listOfRows()
				.forEach(r -> players.computeIfAbsent((UUID) r.get("game_id"), k -> new HashSet<>()).add((UUID) r.get("user_id")));
		}
		var out = new ArrayList<OpenGame>();
		for (var r : rows) {
			var in = new HashSet<>(players.getOrDefault(r.id(), Set.of()));
			in.add(r.hostId());
			out.add(new OpenGame(r.id(), r.title(), r.sport(), r.format(), r.venueName(), r.venueArea(), r.country(), r.startsAt().toInstant(),
					r.timezone(), r.capacity() == null ? null : (int) (r.capacity() - r.players()), r.hostId(), Set.copyOf(in)));
		}
		return out;
	}

	@Override
	@Transactional(readOnly = true)
	public Set<UUID> hostedSince(Instant since, Instant now) {
		return Set.copyOf(jdbc.sql("""
				SELECT DISTINCT host_id FROM games
				WHERE status <> 'cancelled' AND competition_id IS NULL AND starts_at >= :since AND starts_at < :now
				""").param("since", since.atOffset(ZoneOffset.UTC)).param("now", now.atOffset(ZoneOffset.UTC)).query(UUID.class).list());
	}

	@Override
	@Transactional(readOnly = true)
	public Set<UUID> hosting(Instant from, Instant to) {
		var hosts = new HashSet<>(jdbc.sql("""
				SELECT DISTINCT host_id FROM games WHERE status <> 'cancelled' AND starts_at >= :from AND starts_at < :to
				""").param("from", from.atOffset(ZoneOffset.UTC)).param("to", to.atOffset(ZoneOffset.UTC)).query(UUID.class).list());
		hosts.addAll(jdbc.sql("SELECT DISTINCT host_id FROM game_series WHERE status = 'active'").query(UUID.class).list());
		return Set.copyOf(hosts);
	}

	@Override
	public Set<UUID> playing(Instant from, Instant to) {
		return Set.copyOf(jdbc.sql("""
				SELECT DISTINCT p.user_id FROM game_participants p JOIN games g ON g.id = p.game_id
				WHERE p.user_id IS NOT NULL AND g.status <> 'cancelled' AND g.starts_at >= :from AND g.starts_at < :to
				""").param("from", from.atOffset(ZoneOffset.UTC)).param("to", to.atOffset(ZoneOffset.UTC)).query(UUID.class).list());
	}

}
