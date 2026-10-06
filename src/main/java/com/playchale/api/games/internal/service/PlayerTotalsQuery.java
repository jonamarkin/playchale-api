package com.playchale.api.games.internal.service;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.playchale.api.games.api.PlayerTotals;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds players up across games, in the database, for a leaderboard.
 *
 * <p>A player counts for a game only when they were named on a side of a verified result, which is
 * the same rule a profile's record uses: being in the squad and not playing neither helps nor hurts.
 */
@Service
class PlayerTotalsQuery implements PlayerTotals {

	/** No filter may ask for more than this, whatever it sends. */
	private static final int MAX = 100;

	private final JdbcClient jdbc;

	PlayerTotalsQuery(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	private record Row(UUID userId, int games, int wins, int goals, int assists, int points, int sets) {
	}

	@Override
	@Transactional(readOnly = true)
	public List<Totals> top(Filter filter) {
		// The column is chosen from the enum, never from anything a caller typed: it cannot be a
		// value, so it cannot be a parameter, and this is the only reason the string is built.
		var order = switch (filter.metric() == null ? Metric.GOALS : filter.metric()) {
			case GOALS -> "goals";
			case ASSISTS -> "assists";
			case POINTS -> "points";
			case SETS -> "sets";
			case WINS -> "wins";
			case GAMES -> "games";
		};
		// The aggregate is wrapped so the metric can be named once: inside, HAVING and ORDER BY cannot
		// see a SELECT alias (Postgres resolves them against the source columns first), which would
		// mean repeating every sum and filter expression a second time.
		var rows = jdbc.sql("""
				SELECT * FROM (
				  -- Sets are folded to one row per game first. Joining result_sets straight on would
				  -- multiply a player's row by the number of sets and make "games" count sets instead.
				  WITH sets AS (
				    SELECT game_id,
				           count(*) FILTER (WHERE home > away) AS home_sets,
				           count(*) FILTER (WHERE away > home) AS away_sets
				    FROM result_sets GROUP BY game_id
				  )
				  SELECT rp.user_id,
				         count(*)                                                                  AS games,
				         count(*) FILTER (WHERE (rp.side = 'home' AND r.home_score > r.away_score)
				                             OR (rp.side = 'away' AND r.away_score > r.home_score)) AS wins,
				         coalesce(sum(rp.goals), 0)   AS goals,
				         coalesce(sum(rp.assists), 0) AS assists,
				         coalesce(sum(rp.points), 0)  AS points,
				         coalesce(sum(CASE WHEN rp.side = 'home' THEN s.home_sets ELSE s.away_sets END), 0) AS sets
				  FROM result_players rp
				  JOIN game_results r ON r.game_id = rp.game_id
				  JOIN games g        ON g.id = rp.game_id
				  JOIN users u        ON u.id = rp.user_id
				  LEFT JOIN sets s    ON s.game_id = rp.game_id
				  WHERE rp.side IN ('home', 'away')
				    AND u.deleted_at IS NULL
				    AND (CAST(:sport AS text) IS NULL OR g.sport = :sport)
				    AND (CAST(:country AS text) IS NULL OR g.country = :country)
				    AND (CAST(:area AS text) IS NULL OR g.venue_area ILIKE '%%' || CAST(:area AS text) || '%%')
				    AND (CAST(:since AS timestamptz) IS NULL OR g.starts_at >= CAST(:since AS timestamptz))
				  GROUP BY rp.user_id
				) t
				WHERE t.%s > 0
				ORDER BY t.%s DESC, t.games DESC, t.wins DESC
				LIMIT :limit
				""".formatted(order, order))
			.param("sport", blankToNull(filter.sport()))
			.param("country", blankToNull(filter.country()))
			.param("area", blankToNull(filter.area()))
			// The driver takes an offset date-time, as the rest of the module's SQL does; a bare
			// Instant has no type it can bind.
			.param("since", filter.since() == null ? null : filter.since().atOffset(ZoneOffset.UTC))
			.param("limit", Math.clamp(filter.limit() <= 0 ? 20 : filter.limit(), 1, MAX))
			.query(Row.class)
			.list();
		return rows.stream().map(r -> new Totals(r.userId(), r.games(), r.wins(), r.goals(), r.assists(), r.points(), r.sets())).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public List<String> areas(String country) {
		return jdbc.sql("""
				SELECT DISTINCT g.venue_area
				FROM games g
				JOIN game_results r ON r.game_id = g.id
				WHERE g.venue_area IS NOT NULL AND btrim(g.venue_area) <> ''
				  AND (CAST(:country AS text) IS NULL OR g.country = :country)
				ORDER BY g.venue_area
				""").param("country", blankToNull(country)).query(String.class).list();
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

}
