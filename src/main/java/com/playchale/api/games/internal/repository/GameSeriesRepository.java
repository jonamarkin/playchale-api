package com.playchale.api.games.internal.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.games.internal.domain.GameSeries;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface GameSeriesRepository extends JpaRepository<GameSeries, UUID> {

	/** The series, locked until the transaction ends: opening its next game and the host changing it never cross. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from GameSeries s where s.id = :id")
	Optional<GameSeries> lockById(UUID id);

	/** Series whose next game is due to open, longest waiting first. */
	@Query("select s.id from GameSeries s where s.status = 'active' and s.opensAt <= :now order by s.opensAt")
	List<UUID> due(Instant now, Limit limit);

	/** Someone's series, newest first: running ones and the ones they paused or stopped. */
	List<GameSeries> findByHostIdOrderByCreatedAtDesc(UUID hostId);

	Optional<GameSeries> findByLastGameId(UUID gameId);

	/** Of these series, the ones {@code userId} asked not to be invited to. */
	@Query(value = "SELECT series_id FROM game_series_optouts WHERE user_id = :userId AND series_id IN (:seriesIds)", nativeQuery = true)
	List<UUID> optedOut(UUID userId, Collection<UUID> seriesIds);

	/** Of these players, the ones who asked not to be invited to this series. */
	@Query(value = "SELECT user_id FROM game_series_optouts WHERE series_id = :seriesId", nativeQuery = true)
	List<UUID> optedOut(UUID seriesId);

	@Modifying
	@Query(value = "INSERT INTO game_series_optouts (series_id, user_id, at) VALUES (:seriesId, :userId, :at) ON CONFLICT DO NOTHING",
			nativeQuery = true)
	int optOut(UUID seriesId, UUID userId, Instant at);

	@Modifying
	@Query(value = "DELETE FROM game_series_optouts WHERE series_id = :seriesId AND user_id = :userId", nativeQuery = true)
	int optIn(UUID seriesId, UUID userId);

	/** Clears someone's opt-outs when they close their account, as their invites go. */
	@Modifying
	@Query(value = "DELETE FROM game_series_optouts WHERE user_id = :userId", nativeQuery = true)
	void forgetOptOuts(UUID userId);

}
