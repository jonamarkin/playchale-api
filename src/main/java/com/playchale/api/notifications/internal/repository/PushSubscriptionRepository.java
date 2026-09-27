package com.playchale.api.notifications.internal.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.notifications.internal.domain.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, UUID> {

	List<PushSubscription> findByUserId(UUID userId);

	Optional<PushSubscription> findByEndpoint(String endpoint);

	long countByUserId(UUID userId);

	@Modifying
	@Query("delete from PushSubscription s where s.userId = :userId and s.endpoint = :endpoint")
	int deleteMine(UUID userId, String endpoint);

	@Modifying
	@Query("delete from PushSubscription s where s.userId = :userId")
	void deleteAllFor(UUID userId);

}
