package com.playchale.api.auth.internal.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.auth.internal.domain.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SessionRepository extends JpaRepository<Session, String> {

	/** A session that hasn't expired. */
	Optional<Session> findByTokenHashAndExpiresAtAfter(String tokenHash, Instant now);

	@Modifying
	@Query("delete from Session s where s.userId = :userId")
	int deleteAllOf(UUID userId);

}
