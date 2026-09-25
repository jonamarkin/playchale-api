package com.playchale.api.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface SignInCodeRepository extends JpaRepository<SignInCode, UUID> {

	/** How many codes a number has asked for recently, to stop someone flooding a phone with texts. */
	long countByPhoneAndCreatedAtAfter(String phone, Instant since);

	/**
	 * The newest code for a number that hasn't been used or expired. The lock (SELECT ... FOR UPDATE)
	 * holds the row until the transaction ends, so two guesses at the same moment can't both be
	 * counted as the first.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select c from SignInCode c
			where c.phone = :phone and c.usedAt is null and c.expiresAt > :now
			order by c.createdAt desc
			limit 1
			""")
	Optional<SignInCode> findLatestLiveForUpdate(String phone, Instant now);

}
