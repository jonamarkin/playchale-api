package com.playchale.api.auth.internal.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.auth.internal.domain.SignInCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SignInCodeRepository extends JpaRepository<SignInCode, UUID> {

	/** How many codes a phone or address has been sent since a moment, for the hourly limit. */
	long countByRecipientAndCreatedAtAfter(String recipient, Instant since);

	@Modifying
	@Query("delete from SignInCode c where c.recipient in :recipients")
	int deleteAllTo(Collection<String> recipients);

	/**
	 * The newest live code for a phone or address. The lock (SELECT ... FOR UPDATE) holds the row until the
	 * transaction ends, so two guesses at the same moment can't both be counted as the first.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select c from SignInCode c
			where c.recipient = :recipient and c.usedAt is null and c.expiresAt > :now
			order by c.createdAt desc
			limit 1
			""")
	Optional<SignInCode> lockLatestLive(String recipient, Instant now);

}
