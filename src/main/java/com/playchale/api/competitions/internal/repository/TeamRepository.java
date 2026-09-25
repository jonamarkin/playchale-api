package com.playchale.api.competitions.internal.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.competitions.internal.domain.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TeamRepository extends JpaRepository<Team, UUID> {

	List<Team> findByCompetitionIdOrderByCreatedAt(UUID competitionId);

	Optional<Team> findByCompetitionIdAndJoinToken(UUID competitionId, String joinToken);

	/** Whether someone is in any squad in the competition. */
	@Query("select count(t) > 0 from Team t join t.players p where t.competitionId = :competitionId and p.userId = :userId")
	boolean isPlaying(UUID competitionId, UUID userId);

	/** The teams someone plays for, in every competition. */
	@Query("select t from Team t join t.players p where p.userId = :userId order by t.createdAt")
	List<Team> playedForBy(UUID userId);

}
