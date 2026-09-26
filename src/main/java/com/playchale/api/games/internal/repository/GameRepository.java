package com.playchale.api.games.internal.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.games.internal.domain.Game;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface GameRepository extends JpaRepository<Game, UUID> {

	/**
	 * The game, locked until the transaction ends. Every change to a roster goes through this, so
	 * two players can't both take the last spot.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select g from Game g where g.id = :id")
	Optional<Game> lockById(UUID id);

	/**
	 * Upcoming games still on, that the viewer may see, starting in [from, to). {@code sport} is
	 * '' for any; {@code pattern} is a lower-case LIKE pattern over title, format and venue.
	 */
	@Query("""
			select g from Game g
			where g.status in ('open', 'full') and g.startsAt > :now and g.startsAt >= :from and g.startsAt < :to
			  and (g.visibility = 'public' or g.hostId = :viewer
			       or exists (select 1 from Participant p where p.game = g and p.userId = :viewer))
			  and (:sport = '' or g.sport = :sport)
			  and lower(concat(g.title, ' ', g.format, ' ', g.venueName, ' ', coalesce(g.venueArea, ''))) like :pattern
			order by g.startsAt
			""")
	List<Game> discover(Instant now, UUID viewer, String sport, Instant from, Instant to, String pattern, Limit limit);

	List<Game> findByCompetitionIdOrderByStartsAt(UUID competitionId);

	/** Whether someone is hosting a game still to come. */
	@Query("select count(g) > 0 from Game g where g.hostId = :hostId and g.status in ('open', 'full') and g.startsAt > :now")
	boolean isHostingUpcoming(UUID hostId, Instant now);

	/** Games someone hosts or has a spot in, in kick-off order. */
	@Query("""
			select g from Game g
			where g.hostId = :userId or exists (select 1 from Participant p where p.game = g and p.userId = :userId)
			order by g.startsAt
			""")
	List<Game> involving(UUID userId, Limit limit);

}
