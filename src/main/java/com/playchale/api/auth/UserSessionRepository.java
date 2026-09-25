package com.playchale.api.auth;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface UserSessionRepository extends JpaRepository<UserSession, String> {

	/** A session that hasn't expired, with its player loaded in the same query. */
	@Query("select s from UserSession s join fetch s.user where s.tokenHash = :tokenHash and s.expiresAt > :now")
	Optional<UserSession> findLive(String tokenHash, Instant now);

}
