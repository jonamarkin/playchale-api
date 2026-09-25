package com.playchale.api.venues.internal.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.venues.internal.domain.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

	/** The first confirmed booking on a pitch that overlaps [start, end), for a clear message before the database refuses. */
	@Query("""
			select b from Booking b
			where b.pitchId = :pitchId and b.status = 'confirmed' and b.startsAt < :end and b.endsAt > :start
			order by b.startsAt
			limit 1
			""")
	Optional<Booking> findClash(UUID pitchId, Instant start, Instant end);

	/** Confirmed bookings at a venue overlapping [from, to), earliest first. */
	@Query("""
			select b from Booking b
			where b.venueId = :venueId and b.status = 'confirmed' and b.startsAt < :to and b.endsAt > :from
			order by b.startsAt
			""")
	List<Booking> findConfirmedBetween(UUID venueId, Instant from, Instant to);

	boolean existsByPitchIdInAndStatusAndEndsAtAfter(Collection<UUID> pitchIds, String status, Instant now);

	List<Booking> findByGameIdAndStatus(UUID gameId, String status);

}
