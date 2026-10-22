package com.playchale.api.games.internal.repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.playchale.api.games.internal.domain.ResultVote;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Picks for the player of the match: one per player per game, and which closed votes are still to be announced. */
@Component
public class ResultVotes {

	private final JdbcClient jdbc;

	ResultVotes(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** A player's pick; picking again replaces it. */
	public void cast(UUID gameId, UUID voter, String playerKey, Instant now) {
		jdbc.sql("""
				INSERT INTO result_votes (game_id, voter_id, player_key, voted_at) VALUES (:game, :voter, :key, :now)
				ON CONFLICT (game_id, voter_id) DO UPDATE SET player_key = EXCLUDED.player_key, voted_at = EXCLUDED.voted_at
				""").param("game", gameId).param("voter", voter).param("key", playerKey).param("now", now.atOffset(ZoneOffset.UTC))
			.update();
	}

	/** Every pick in these games, by game. */
	public Map<UUID, List<ResultVote>> ofGames(Collection<UUID> gameIds) {
		if (gameIds.isEmpty()) {
			return Map.of();
		}
		return jdbc.sql("SELECT game_id, voter_id, player_key FROM result_votes WHERE game_id IN (:games)")
			.param("games", gameIds)
			.query((rs, n) -> Map.entry(rs.getObject("game_id", UUID.class),
					new ResultVote(rs.getObject("voter_id", UUID.class), rs.getString("player_key"))))
			.list().stream()
			.collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
	}

	/** Games whose voting has closed with at least one pick and nobody told yet. */
	public List<UUID> closedUnannounced(Instant now) {
		return jdbc.sql("""
				SELECT r.game_id FROM game_results r
				WHERE r.votes_announced_at IS NULL AND r.votes_close_at <= :now
				  AND EXISTS (SELECT 1 FROM result_votes v WHERE v.game_id = r.game_id)
				ORDER BY r.votes_close_at
				LIMIT 200
				""").param("now", now.atOffset(ZoneOffset.UTC)).query(UUID.class).list();
	}

	/** Takes this game's announcement, unless it's been made. */
	public boolean claimAnnouncement(UUID gameId, Instant now) {
		return jdbc.sql("UPDATE game_results SET votes_announced_at = :now WHERE game_id = :game AND votes_announced_at IS NULL")
			.param("game", gameId).param("now", now.atOffset(ZoneOffset.UTC)).update() == 1;
	}

	/** A closed account's picks go. Picks for them stay: they did play, and were picked. */
	public void forget(UUID userId) {
		jdbc.sql("DELETE FROM result_votes WHERE voter_id = :user").param("user", userId).update();
	}

}
