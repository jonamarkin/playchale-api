package com.playchale.api.competitions.internal.service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.playchale.api.competitions.api.CompetitionDigest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The competition half of someone's weekly round-up, straight from the fixture tables.
 *
 * <p>"Their competitions" means the ones they are actually in: a squad they play for, a team they
 * captain, or one they organise. Two plain queries, because this is a report rather than anything
 * the app edits.
 */
@Component
class CompetitionDigestQuery implements CompetitionDigest {

	/** The competitions this person is in, however they're in them. Shared by both queries. */
	private static final String THEIRS = """
			SELECT c.id
			FROM competitions c
			WHERE c.organiser_id = :user
			   OR EXISTS (SELECT 1 FROM competition_organisers o WHERE o.competition_id = c.id AND o.user_id = :user)
			   OR EXISTS (SELECT 1 FROM team_players tp WHERE tp.competition_id = c.id AND tp.user_id = :user)
			   OR EXISTS (SELECT 1 FROM teams t WHERE t.competition_id = c.id AND t.captain_id = :user)
			""";

	/** The team they belong to in that competition, for "your team won"; null when they only run it. */
	private static final String THEIR_TEAM = """
			(SELECT t.name FROM teams t
			 LEFT JOIN team_players tp ON tp.team_id = t.id AND tp.user_id = :user
			 WHERE t.competition_id = c.id AND (tp.user_id IS NOT NULL OR t.captain_id = :user)
			 LIMIT 1)
			""";

	private final JdbcClient jdbc;

	private final Clock clock;

	CompetitionDigestQuery(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public List<CompetitionResult> since(UUID userId, Instant since) {
		return jdbc.sql("""
				SELECT c.id AS competition_id, c.name AS competition, home.name AS home_team, away.name AS away_team,
				       r.home_score, r.away_score, r.recorded_at AS played_at, %s AS theirs
				FROM games g
				JOIN game_results r ON r.game_id = g.id
				JOIN competitions c ON c.id = g.competition_id
				JOIN teams home ON home.id = g.home_team_id
				JOIN teams away ON away.id = g.away_team_id
				WHERE g.competition_id IN (%s) AND r.recorded_at >= :since
				ORDER BY r.recorded_at DESC
				""".formatted(THEIR_TEAM, THEIRS))
			.param("user", userId).param("since", since.atOffset(ZoneOffset.UTC))
			.query(Row.class).list().stream().map(Row::toResult).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public List<CompetitionResult> upcoming(UUID userId, Instant until) {
		return jdbc.sql("""
				SELECT c.id AS competition_id, c.name AS competition, home.name AS home_team, away.name AS away_team,
				       0 AS home_score, 0 AS away_score, g.starts_at AS played_at, %s AS theirs
				FROM games g
				JOIN competitions c ON c.id = g.competition_id
				JOIN teams home ON home.id = g.home_team_id
				JOIN teams away ON away.id = g.away_team_id
				WHERE g.competition_id IN (%s)
				  AND g.status <> 'cancelled'
				  AND NOT EXISTS (SELECT 1 FROM game_results r WHERE r.game_id = g.id)
				  AND g.starts_at BETWEEN :now AND :until
				ORDER BY g.starts_at
				""".formatted(THEIR_TEAM, THEIRS))
			.param("user", userId).param("now", clock.instant().atOffset(ZoneOffset.UTC)).param("until", until.atOffset(ZoneOffset.UTC))
			.query(Row.class).list().stream().map(Row::toResult).toList();
	}

	private record Row(UUID competitionId, String competition, String homeTeam, String awayTeam, int homeScore, int awayScore,
			OffsetDateTime playedAt, String theirs) {

		CompetitionResult toResult() {
			return new CompetitionResult(competitionId, competition, homeTeam, awayTeam, homeScore, awayScore, playedAt.toInstant(), theirs);
		}

	}

}
