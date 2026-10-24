package com.playchale.api.games.internal.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.playchale.api.games.api.WeekInGames;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Reads the week straight from the result tables, counting votes the way the result card does (GameResult.tally). */
@Component
class WeekInGamesQuery implements WeekInGames {

	private final JdbcClient jdbc;

	WeekInGamesQuery(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	private record Row(UUID gameId, String title, String country, String venueArea, String playerKey) {
	}

	private record Count(String country, int games, int players) {
	}

	@Override
	@Transactional(readOnly = true)
	public List<PlayerOfTheMatch> playersOfTheMatchBetween(Instant since, Instant now) {
		return jdbc.sql("""
				WITH counted AS (
				  SELECT v.game_id, v.player_key, count(*) AS n
				  FROM result_votes v
				  JOIN game_results r ON r.game_id = v.game_id
				  WHERE r.votes_close_at > :since AND r.votes_close_at <= :now AND v.player_key <> CAST(v.voter_id AS text)
				    AND EXISTS (SELECT 1 FROM result_players p WHERE p.game_id = v.game_id AND p.user_id = v.voter_id AND p.side IN ('home', 'away'))
				    AND EXISTS (SELECT 1 FROM result_players p WHERE p.game_id = v.game_id AND p.player_key = v.player_key AND p.side IN ('home', 'away'))
				  GROUP BY v.game_id, v.player_key
				), ranked AS (
				  SELECT game_id, player_key, n, max(n) OVER (PARTITION BY game_id) AS top FROM counted
				)
				SELECT g.id AS game_id, g.title, g.country, g.venue_area, ranked.player_key
				FROM ranked JOIN games g ON g.id = ranked.game_id
				WHERE ranked.n = ranked.top
				ORDER BY g.starts_at DESC
				""").param("since", since.atOffset(ZoneOffset.UTC)).param("now", now.atOffset(ZoneOffset.UTC)).query(Row.class).list().stream()
			.map(r -> new PlayerOfTheMatch(r.gameId(), r.title(), r.country(), r.venueArea(), r.playerKey()))
			.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Map<String, Played> playedSince(Instant since) {
		return jdbc.sql("""
				SELECT g.country, count(DISTINCT g.id) AS games, count(DISTINCT p.user_id) AS players
				FROM games g
				JOIN game_results r ON r.game_id = g.id
				JOIN result_players p ON p.game_id = g.id AND p.side IN ('home', 'away')
				WHERE g.starts_at >= :since
				GROUP BY g.country
				""").param("since", since.atOffset(ZoneOffset.UTC)).query(Count.class).list().stream()
			.collect(Collectors.toMap(Count::country, c -> new Played(c.games(), c.players())));
	}

}
