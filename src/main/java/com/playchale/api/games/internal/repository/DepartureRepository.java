package com.playchale.api.games.internal.repository;

import java.util.List;
import java.util.UUID;

import com.playchale.api.games.internal.domain.Departure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DepartureRepository extends JpaRepository<Departure, UUID> {

	/** Someone's own drop-outs, most recent first. Spots a host took back aren't theirs to answer for. */
	@Query("select d from Departure d where d.userId = :userId and d.reason = 'left' order by d.leftAt desc")
	List<Departure> leftBy(UUID userId);

	/**
	 * Clears someone's record when they close their account. The users row is only anonymised
	 * (V11), so nothing is removed for us: without this their drop-outs would outlive the account.
	 */
	@Modifying
	@Query("delete from Departure d where d.userId = :userId")
	void forget(UUID userId);

}
