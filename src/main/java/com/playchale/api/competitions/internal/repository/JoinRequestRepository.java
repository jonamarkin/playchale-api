package com.playchale.api.competitions.internal.repository;

import java.util.List;
import java.util.UUID;

import com.playchale.api.competitions.internal.domain.JoinRequest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JoinRequestRepository extends JpaRepository<JoinRequest, UUID> {

	List<JoinRequest> findByCompetitionIdAndStatusOrderByCreatedAt(UUID competitionId, String status);

	boolean existsByTeamIdAndUserIdAndStatus(UUID teamId, UUID userId, String status);

}
