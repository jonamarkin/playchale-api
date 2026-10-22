package com.playchale.api.games.internal.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.playchale.api.games.api.PlayedGames;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a player's record straight from the result tables in three queries, whatever their number of
 * games: plain SQL for a read that's all about the data, as reports are.
 */
@Component
class PlayedGamesQuery implements PlayedGames {

	private final JdbcClient jdbc;

	private final Clock clock;

	PlayedGamesQuery(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	private record Row(UUID gameId, String title, String sport, String format, OffsetDateTime startsAt, String venueName,
			String side, int homeScore, int awayScore, int goals, int assists, int points) {
	}

	private record SetRow(UUID gameId, int home, int away) {
	}

	@Override
	@Transactional(readOnly = true)
	public List<PlayedGame> by(UUID userId) {
		var rows = jdbc.sql("""
				SELECT g.id AS game_id, g.title, g.sport, g.format, g.starts_at, g.venue_name,
				       rp.side, r.home_score, r.away_score, rp.goals, rp.assists, rp.points
				FROM result_players rp
				JOIN game_results r ON r.game_id = rp.game_id
				JOIN games g ON g.id = rp.game_id
				WHERE rp.user_id = :user AND rp.side IN ('home', 'away')
				ORDER BY g.starts_at DESC
				""").param("user", userId).query(Row.class).list();
		if (rows.isEmpty()) {
			return List.of();
		}
		Map<UUID, List<SetRow>> sets = jdbc.sql("SELECT game_id, home, away FROM result_sets WHERE game_id IN (:games) ORDER BY game_id, number")
			.param("games", rows.stream().map(Row::gameId).toList())
			.query(SetRow.class)
			.stream()
			.collect(Collectors.groupingBy(SetRow::gameId));
		// Games whose vote has closed with them on top, counted as the result card counts it (GameResult.tally):
		// picks by and for players on a side, never for themselves; level at the top, they share it.
		var voted = new HashSet<>(jdbc.sql("""
				WITH counted AS (
				  SELECT v.game_id, v.player_key, count(*) AS n
				  FROM result_votes v
				  JOIN game_results r ON r.game_id = v.game_id
				  WHERE v.game_id IN (:games) AND r.votes_close_at <= :now AND v.player_key <> CAST(v.voter_id AS text)
				    AND EXISTS (SELECT 1 FROM result_players p WHERE p.game_id = v.game_id AND p.user_id = v.voter_id AND p.side IN ('home', 'away'))
				    AND EXISTS (SELECT 1 FROM result_players p WHERE p.game_id = v.game_id AND p.player_key = v.player_key AND p.side IN ('home', 'away'))
				  GROUP BY v.game_id, v.player_key
				)
				SELECT game_id FROM (SELECT game_id, player_key, n, max(n) OVER (PARTITION BY game_id) AS top FROM counted) ranked
				WHERE n = top AND player_key = :key
				""").param("games", rows.stream().map(Row::gameId).toList()).param("now", clock.instant().atOffset(ZoneOffset.UTC))
			.param("key", userId.toString()).query(UUID.class).list());

		var played = new ArrayList<PlayedGame>();
		for (var r : rows) {
			var home = "home".equals(r.side());
			var ownSets = sets.getOrDefault(r.gameId(), List.of()).stream()
				.map(s -> home ? new PlayedGames.Set(s.home(), s.away()) : new PlayedGames.Set(s.away(), s.home()))
				.toList();
			played.add(new PlayedGame(r.gameId(), r.title(), r.sport(), r.format(), r.startsAt().toInstant(), r.venueName(),
					home ? r.homeScore() : r.awayScore(), home ? r.awayScore() : r.homeScore(), r.goals(), r.assists(), r.points(), ownSets,
					voted.contains(r.gameId())));
		}
		return played;
	}

}
