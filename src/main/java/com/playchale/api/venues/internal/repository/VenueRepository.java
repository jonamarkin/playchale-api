package com.playchale.api.venues.internal.repository;

import java.util.List;
import java.util.UUID;

import com.playchale.api.venues.internal.domain.Venue;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface VenueRepository extends JpaRepository<Venue, UUID> {

	/** Listed venues whose name or area contains the text (lower-case, with % around it). */
	@Query("""
			select v from Venue v
			where v.listed = true and lower(concat(v.name, ' ', v.area)) like :pattern
			order by v.name
			""")
	List<Venue> searchListed(String pattern, Limit limit);

	List<Venue> findByOwnerIdOrderByCreatedAt(UUID ownerId);

	boolean existsByOwnerId(UUID ownerId);

}
