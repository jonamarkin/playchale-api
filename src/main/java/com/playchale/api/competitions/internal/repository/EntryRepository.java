package com.playchale.api.competitions.internal.repository;

import java.util.List;
import java.util.UUID;

import com.playchale.api.competitions.internal.domain.Entry;
import com.playchale.api.competitions.internal.domain.EntryId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EntryRepository extends JpaRepository<Entry, EntryId> {

	@Query("select e from Entry e where e.id.competitionId = :competitionId order by e.enteredAt")
	List<Entry> inCompetition(UUID competitionId);

	@Query("select e from Entry e where e.id.teamId = :teamId order by e.enteredAt")
	List<Entry> ofTeam(UUID teamId);

	/** Whether someone is in any squad in the competition. */
	@Query("select count(e) > 0 from Entry e join e.players p where e.id.competitionId = :competitionId and p.userId = :userId")
	boolean isPlaying(UUID competitionId, UUID userId);

}
