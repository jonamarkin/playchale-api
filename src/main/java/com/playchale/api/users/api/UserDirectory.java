package com.playchale.api.users.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** How other modules find players, and how sign-in registers them. */
public interface UserDirectory {

	Optional<UserSummary> find(UUID id);

	/**
	 * Many players in one query, for screens that show lots of people (a roster, a league). IDs with
	 * no player are simply missing from the map.
	 */
	Map<UUID, UserSummary> findAll(Collection<UUID> ids);

	/** The player with a public handle, ignoring case. Players without a handle yet can't be found this way. */
	Optional<UserSummary> findByHandle(String handle);

	/** The player with this number (E.164), registered on their first sign-in. */
	UserSummary registerOrFind(String phone, String country);

	/** The player who signs in with this email (lower-case), registered on their first sign-in. */
	UserSummary registerOrFindByEmail(String email, String country);

}
