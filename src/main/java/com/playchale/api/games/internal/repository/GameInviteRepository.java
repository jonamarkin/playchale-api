package com.playchale.api.games.internal.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.playchale.api.games.internal.domain.GameInvite;
import com.playchale.api.games.internal.domain.InviteId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface GameInviteRepository extends JpaRepository<GameInvite, InviteId> {

	@Query("select i from GameInvite i where i.id.gameId in :gameIds order by i.invitedAt")
	List<GameInvite> ofGames(Collection<UUID> gameIds);

	/** Games someone is invited to and hasn't answered, still to come and still on, soonest first. */
	@Query("""
			select g.id from GameInvite i, Game g
			where g.id = i.id.gameId and i.id.userId = :userId and i.status = 'pending'
			  and g.status in ('open', 'full') and g.startsAt > :now
			order by g.startsAt
			""")
	List<UUID> waitingFor(UUID userId, Instant now);

	@Modifying
	@Query("delete from GameInvite i where i.id.userId = :userId")
	void forget(UUID userId);

}
