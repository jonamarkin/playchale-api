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

	/**
	 * The player a Google account belongs to: the one already signing in with it, else the one who
	 * signs in with its (Google-verified) email, which it's then connected to, else a new player.
	 */
	UserSummary registerOrFindByGoogle(String googleSub, String email, String country);

	/** Who signs in with this phone (E.164, method "phone") or email (lower-case, method "email"), if anyone. */
	Optional<UUID> signsInWith(String method, String address);

	/**
	 * Lets a player sign in with this phone or email too, in place of the one they had. The caller has
	 * checked they hold it (a code) and that nobody else signs in with it.
	 */
	UserSummary addSignInMethod(UUID userId, String method, String address);

	/** Stops a player signing in with their phone or email. They must have the other. */
	UserSummary removeSignInMethod(UUID userId, String method);

}
