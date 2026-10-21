package com.playchale.api.notifications.internal.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.notifications.internal.domain.AppPushToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AppPushTokenRepository extends JpaRepository<AppPushToken, UUID> {

	List<AppPushToken> findByUserId(UUID userId);

	Optional<AppPushToken> findByToken(String token);

	long countByUserId(UUID userId);

	@Modifying
	@Query("delete from AppPushToken t where t.userId = :userId and t.token = :token")
	int deleteMine(UUID userId, String token);

	@Modifying
	@Query("delete from AppPushToken t where t.userId = :userId")
	void deleteAllFor(UUID userId);

}
