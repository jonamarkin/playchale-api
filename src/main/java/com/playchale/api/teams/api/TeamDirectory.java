package com.playchale.api.teams.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Teams, for other modules: leagues enter them, games invite them, profiles list them.
 *
 * <p>{@code announce} says whether the teams module tells people itself (a notification about the
 * team). Leagues pass false and tell people about their squad in their own words.
 */
public interface TeamDirectory {

	Optional<TeamCard> find(UUID teamId);

	Map<UUID, TeamCard> findAll(Collection<UUID> teamIds);

	/** Teams someone is in or captains, oldest first. */
	List<TeamCard> teamsOf(UUID userId);

	/**
	 * A team set up for someone else's use, e.g. an organiser's school side. {@code captainPlays}:
	 * whether the captain is a member too.
	 */
	TeamCard create(String name, UUID captainId, boolean captainPlays, String tint, Collection<UUID> memberIds);

	/** The team a join link belongs to. */
	Optional<TeamCard> byToken(String token);

	/** The team's join link token, for its captain (and a league organiser running its squad). */
	String joinToken(UUID teamId);

	/** Adds people who aren't in yet. Returns those actually added. */
	List<UUID> addMembers(UUID teamId, Collection<UUID> userIds, UUID addedBy, boolean announce);

	/** Someone asks the captain for a place. */
	void requestJoin(UUID teamId, UUID userId, boolean announce);

	List<JoinRequestCard> pendingRequests(Collection<UUID> teamIds);

	/** The captain answers a request; saying yes adds them. */
	JoinRequestCard answerRequest(UUID requestId, boolean accept, UUID captain, boolean announce);

}
