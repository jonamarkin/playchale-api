package com.playchale.api.games.internal.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import com.playchale.api.games.api.UpcomingGames;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** UpcomingGames, read straight from the games tables: two queries whatever the number of games. */
@Component
class UpcomingGamesQuery implements UpcomingGames {

	private final JdbcClient jdbc;

	UpcomingGamesQuery(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	private record Row(UUID id, String title, String venueName, String country, OffsetDateTime startsAt, String timezone, Integer capacity,
			UUID hostId) {
	}

	@Override
	@Transactional(readOnly = true)
	public List<Upcoming> startingBetween(Instant from, Instant to) {
		var rows = jdbc.sql("""
				SELECT id, title, venue_name, country, starts_at, timezone, capacity, host_id
				FROM games
				WHERE status IN ('open', 'full') AND competition_id IS NULL AND starts_at >= :from AND starts_at < :to
				ORDER BY starts_at
				""").param("from", from.atOffset(ZoneOffset.UTC)).param("to", to.atOffset(ZoneOffset.UTC)).query(Row.class).list();
		var players = new HashMap<UUID, List<UUID>>();
		var guests = new HashMap<UUID, List<String>>();
		var filled = new HashMap<UUID, Integer>();
		if (!rows.isEmpty()) {
			jdbc.sql("SELECT game_id, user_id, guest_phone FROM game_participants WHERE game_id IN (:ids) ORDER BY joined_at")
				.param("ids", rows.stream().map(Row::id).toList()).query().listOfRows().forEach(r -> {
					var game = (UUID) r.get("game_id");
					filled.merge(game, 1, Integer::sum);
					if (r.get("user_id") != null) {
						players.computeIfAbsent(game, k -> new ArrayList<>()).add((UUID) r.get("user_id"));
					}
					else if (r.get("guest_phone") != null) {
						guests.computeIfAbsent(game, k -> new ArrayList<>()).add((String) r.get("guest_phone"));
					}
				});
		}
		return rows.stream().map(r -> new Upcoming(r.id(), r.title(), r.venueName(), r.country(), r.startsAt().toInstant(), r.timezone(),
				r.capacity(), filled.getOrDefault(r.id(), 0), r.hostId(), List.copyOf(players.getOrDefault(r.id(), List.of())),
				List.copyOf(guests.getOrDefault(r.id(), List.of())))).toList();
	}

}
