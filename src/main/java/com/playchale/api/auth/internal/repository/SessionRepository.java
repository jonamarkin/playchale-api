package com.playchale.api.auth.internal.repository;

import java.time.Instant;
import java.util.Optional;

import com.playchale.api.auth.internal.domain.Session;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionRepository extends JpaRepository<Session, String> {

	/** A session that hasn't expired. */
	Optional<Session> findByTokenHashAndExpiresAtAfter(String tokenHash, Instant now);

}
