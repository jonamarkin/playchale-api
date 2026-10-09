package com.playchale.api.games.internal.repository;

import java.util.UUID;

import com.playchale.api.games.internal.domain.GameResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface GameResultRepository extends JpaRepository<GameResult, UUID> {

	/** A guest's line in a result becomes a player's, when they claim the spot after the game. */
	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE result_players SET player_key = :playerKey, user_id = :userId
			WHERE game_id = :gameId AND player_key = :guestKey
			""", nativeQuery = true)
	int rekey(UUID gameId, String guestKey, String playerKey, UUID userId);

}
