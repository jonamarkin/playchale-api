package com.playchale.api.competitions.internal.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.competitions.internal.domain.Competition;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface CompetitionRepository extends JpaRepository<Competition, UUID> {

	/** Locked until the transaction ends: every squad change goes through this, one at a time per league. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Competition c where c.id = :id")
	Optional<Competition> lockById(UUID id);

	List<Competition> findByStatusNotOrderByCreatedAtDesc(String status, Limit limit);

	/** Competitions someone organises or plays in, newest first. */
	@Query("""
			select c from Competition c
			where c.organiserId = :userId
			   or exists (select 1 from Team t join t.players p where t.competitionId = c.id and p.userId = :userId)
			order by c.createdAt desc
			""")
	List<Competition> involving(UUID userId, Limit limit);

}
