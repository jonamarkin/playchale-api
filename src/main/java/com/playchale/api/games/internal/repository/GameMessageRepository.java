package com.playchale.api.games.internal.repository;

import java.util.List;
import java.util.UUID;

import com.playchale.api.games.internal.domain.GameMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface GameMessageRepository extends JpaRepository<GameMessage, UUID> {

	/** One game's talk, oldest first: it reads as a conversation, not a feed. */
	List<GameMessage> findByGameIdOrderByCreatedAt(UUID gameId);

	/**
	 * Clears what someone wrote when they close their account. The users row is only anonymised
	 * (V11), so without this their messages would outlive the account.
	 */
	@Modifying
	@Query("delete from GameMessage m where m.userId = :userId")
	void forget(UUID userId);

}
