package com.playchale.api.teams.internal.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.playchale.api.teams.internal.domain.JoinRequest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JoinRequestRepository extends JpaRepository<JoinRequest, UUID> {

	List<JoinRequest> findByTeamIdInAndStatusOrderByCreatedAt(Collection<UUID> teamIds, String status);

	boolean existsByTeamIdAndUserIdAndStatus(UUID teamId, UUID userId, String status);

	List<JoinRequest> findByUserIdAndStatus(UUID userId, String status);

}
