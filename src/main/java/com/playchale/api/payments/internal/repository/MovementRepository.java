package com.playchale.api.payments.internal.repository;

import java.util.List;
import java.util.UUID;

import com.playchale.api.payments.internal.domain.Movement;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MovementRepository extends JpaRepository<Movement, UUID> {

	List<Movement> findByUserIdOrderByCreatedAtDescIdDesc(UUID userId, Limit limit);

}
