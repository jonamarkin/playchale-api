package com.playchale.api.teams.internal.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.teams.internal.domain.Team;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface TeamRepository extends JpaRepository<Team, UUID> {

	/** Locked until the transaction ends: membership changes to one team go one at a time. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from Team t where t.id = :id")
	Optional<Team> lockById(UUID id);

	Optional<Team> findByJoinToken(String joinToken);

	/** Teams someone plays for or captains, oldest first. */
	@Query("""
			select distinct t from Team t left join t.members m
			where t.captainId = :userId or m.userId = :userId
			order by t.createdAt
			""")
	List<Team> involving(UUID userId);

	/** Teams someone plays for (not only captains). */
	@Query("select t from Team t join t.members m where m.userId = :userId order by t.createdAt")
	List<Team> playedForBy(UUID userId);

	List<Team> findByCaptainId(UUID captainId);

	/** Teams whose name has this in it, for finding one to play. */
	List<Team> findTop20ByNameContainingIgnoreCaseOrderByName(String name);

}
