package com.playchale.api.notifications.internal.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.playchale.api.notifications.internal.domain.Notification;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

	List<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Limit limit);

	@Modifying
	@Query("update Notification n set n.readAt = :now where n.userId = :userId and n.readAt is null")
	int markAllRead(UUID userId, Instant now);

	@Modifying
	@Query("delete from Notification n where n.userId = :userId")
	int deleteAllFor(UUID userId);

}
