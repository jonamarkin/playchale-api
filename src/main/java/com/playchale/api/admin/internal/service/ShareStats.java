package com.playchale.api.admin.internal.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.playchale.api.games.api.OpenGames;
import com.playchale.api.shared.error.BusinessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the desk's share images are drawn from: a match's result, the week's recap, how many have
 * joined, and the games coming up. Real numbers only, read straight from the tables as the rest of
 * the desk is; and people by their first name only, since these go out as promotion.
 */
@Service
public class ShareStats {

	private final Staff staff;

	private final JdbcClient jdbc;

	private final OpenGames open;

	private final Clock clock;

	ShareStats(Staff staff, JdbcClient jdbc, OpenGames open, Clock clock) {
		this.staff = staff;
		this.jdbc = jdbc;
		this.open = open;
		this.clock = clock;
	}

	/** A player on a result, by first name. */
	public record Player(String name, int goals, int assists, int points) {
	}

	/** One side: what it's called ("Kojo's side", or a team's name), its score, and who played. */
	public record Side(String label, int score, List<Player> players) {
	}

	public record SetScore(int home, int away) {
	}

	/** A played game's result, as a share image shows it. */
	public record Result(UUID id, String title, String sport, Instant startsAt, String timezone, String venueName, String venueArea, Side home,
			Side away, List<SetScore> sets) {
	}

	/** A scorer over the week, by first name. */
	public record Scorer(String name, int goals) {
	}

	/** The week: how many games and players, the goals, the results (latest first), and the top scorers. */
	public record Recap(Instant from, Instant to, int games, int players, int goals, List<Result> results, List<Scorer> topScorers) {
	}

	/** So far: people who've joined, games played and put on, and sports played. */
	public record Milestone(int members, int gamesPlayed, int gamesPutOn, int sports) {
	}

	/** A game anyone can still join. */
	public record Upcoming(UUID id, String title, String sport, Instant startsAt, String timezone, String venueName, String venueArea,
			Integer spotsLeft) {
	}

	private record GameRow(UUID id, String title, String sport, OffsetDateTime startsAt, String timezone, String venueName, String venueArea,
			int homeScore, int awayScore, String hostName, String homeTeam, String awayTeam) {
	}

	private record Line(String side, String name, int goals, int assists, int points) {
	}

	@Transactional(readOnly = true)
	public Result result(UUID by, UUID gameId) {
		staff.require(by);
		return find(gameId).orElseThrow(() -> BusinessException.notFound("That game has no result to share."));
	}

	@Transactional(readOnly = true)
	public Recap recap(UUID by, int days) {
		staff.require(by);
		var to = clock.instant();
		var from = to.minus(Duration.ofDays(Math.clamp(days <= 0 ? 7 : days, 1, 90)));
		var played = jdbc.sql("""
				SELECT r.game_id FROM game_results r JOIN games g ON g.id = r.game_id
				WHERE g.starts_at >= :from AND g.starts_at < :to ORDER BY g.starts_at DESC
				""").param("from", utc(from)).param("to", utc(to)).query(UUID.class).list();
		var totals = jdbc.sql("""
				SELECT count(DISTINCT coalesce(rp.user_id::text, rp.game_id::text || rp.player_key)) AS players, coalesce(sum(rp.goals), 0) AS goals
				FROM result_players rp JOIN games g ON g.id = rp.game_id
				WHERE rp.side IN ('home', 'away') AND g.starts_at >= :from AND g.starts_at < :to
				""").param("from", utc(from)).param("to", utc(to)).query().singleRow();
		var scorers = jdbc.sql("""
				SELECT coalesce(CASE WHEN u.deleted_at IS NULL THEN u.name END, gp.guest_name) AS name, sum(rp.goals) AS goals
				FROM result_players rp
				JOIN games g ON g.id = rp.game_id
				LEFT JOIN users u ON u.id = rp.user_id
				LEFT JOIN game_participants gp ON rp.user_id IS NULL AND rp.player_key = 'guest:' || gp.id::text
				WHERE rp.side IN ('home', 'away') AND g.starts_at >= :from AND g.starts_at < :to
				GROUP BY coalesce(rp.user_id::text, rp.game_id::text || rp.player_key), 1
				HAVING sum(rp.goals) > 0
				ORDER BY 2 DESC, 1
				LIMIT 3
				""").param("from", utc(from)).param("to", utc(to)).query((rs, n) -> new Scorer(firstName(rs.getString("name")), rs.getInt("goals")))
			.list().stream().filter(s -> !s.name().isEmpty()).toList();
		var results = played.stream().map(this::find).flatMap(java.util.Optional::stream).toList();
		return new Recap(from, to, played.size(), ((Number) totals.get("players")).intValue(), ((Number) totals.get("goals")).intValue(), results,
				scorers);
	}

	@Transactional(readOnly = true)
	public Milestone milestone(UUID by) {
		staff.require(by);
		var row = jdbc.sql("""
				SELECT (SELECT count(*) FROM users WHERE onboarded AND NOT guest AND deleted_at IS NULL) AS members,
				       (SELECT count(*) FROM game_results) AS played,
				       (SELECT count(*) FROM games WHERE status <> 'cancelled') AS put_on,
				       (SELECT count(DISTINCT g.sport) FROM games g JOIN game_results r ON r.game_id = g.id) AS sports
				""").query().singleRow();
		return new Milestone(((Number) row.get("members")).intValue(), ((Number) row.get("played")).intValue(),
				((Number) row.get("put_on")).intValue(), ((Number) row.get("sports")).intValue());
	}

	@Transactional(readOnly = true)
	public List<Upcoming> upcoming(UUID by, int days) {
		staff.require(by);
		var now = clock.instant();
		return open.between(now, now.plus(Duration.ofDays(Math.clamp(days <= 0 ? 7 : days, 1, 30)))).stream()
			.map(g -> new Upcoming(g.id(), g.title(), g.sport(), g.startsAt(), g.timezone(), g.venueName(), g.venueArea(), g.spotsLeft()))
			.toList();
	}

	private java.util.Optional<Result> find(UUID gameId) {
		return jdbc.sql("""
				SELECT g.id, g.title, g.sport, g.starts_at, g.timezone, g.venue_name, g.venue_area, r.home_score, r.away_score,
				       h.name AS host_name, home_t.name AS home_team, away_t.name AS away_team
				FROM game_results r
				JOIN games g      ON g.id = r.game_id
				JOIN users h      ON h.id = g.host_id
				LEFT JOIN teams home_t ON home_t.id = g.home_team_id
				LEFT JOIN teams away_t ON away_t.id = g.away_team_id
				WHERE r.game_id = :id
				""").param("id", gameId).query(GameRow.class).optional().map(g -> {
			// Deleted accounts keep their lines but lose their name; guests are known by the one they gave.
			var lines = jdbc.sql("""
					SELECT rp.side, coalesce(CASE WHEN u.deleted_at IS NULL THEN u.name END, gp.guest_name) AS name, rp.goals, rp.assists, rp.points
					FROM result_players rp
					LEFT JOIN users u ON u.id = rp.user_id
					LEFT JOIN game_participants gp ON rp.user_id IS NULL AND rp.player_key = 'guest:' || gp.id::text
					WHERE rp.game_id = :id AND rp.side IN ('home', 'away')
					ORDER BY rp.goals + rp.points DESC, rp.assists DESC, name
					""").param("id", gameId).query(Line.class).list();
			var sets = jdbc.sql("SELECT home, away FROM result_sets WHERE game_id = :id ORDER BY number").param("id", gameId).query(SetScore.class)
				.list();
			var hostSide = firstName(g.hostName()).isEmpty() ? "Home side" : firstName(g.hostName()) + "’s side";
			return new Result(g.id(), g.title(), g.sport(), g.startsAt().toInstant(), g.timezone(), g.venueName(), g.venueArea(),
					new Side(g.homeTeam() != null ? g.homeTeam() : hostSide, g.homeScore(), players(lines, "home")),
					new Side(g.awayTeam() != null ? g.awayTeam() : "Other side", g.awayScore(), players(lines, "away")), sets);
		});
	}

	private static List<Player> players(List<Line> lines, String side) {
		return lines.stream().filter(l -> side.equals(l.side()) && !firstName(l.name()).isEmpty())
			.map(l -> new Player(firstName(l.name()), l.goals(), l.assists(), l.points())).toList();
	}

	/** The first word of a name: what a share image says, never the whole of it. */
	static String firstName(String name) {
		return name == null || name.isBlank() ? "" : name.strip().split("\\s+")[0];
	}

	private static OffsetDateTime utc(Instant at) {
		return at.atOffset(ZoneOffset.UTC);
	}

}
