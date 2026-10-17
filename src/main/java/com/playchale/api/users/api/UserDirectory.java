package com.playchale.api.users.api;

import java.util.Collection;
import java.util.List;
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

	/**
	 * Everyone with a set-up account (not a guest host, not closed), for telling them about games near
	 * them. Read whole: fine for thousands of players; page it before there are hundreds of thousands.
	 */
	List<UserSummary> members();

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

	/**
	 * Someone hosting a game without an account: a guest host, under their name and the number they
	 * gave (E.164, unchecked), who agreed to the Terms ({@code termsVersion}, the current ones).
	 * Refused if the number has an account (they sign in with it instead), or is already hosting as a
	 * guest on another phone.
	 */
	UserSummary registerGuestHost(String name, String phone, String country, String termsVersion);

	/** Whether this is a guest host, who hosts invite-only games and nothing else until they sign up. */
	boolean isGuest(UUID id);

	/**
	 * A guest host signing in on the device they host from, with a phone ("phone"), email ("email")
	 * or Google account ("google", its sub, with its verified {@code email}) that has no account: the
	 * same account, theirs to sign in with now (GuestHostBecameMember).
	 */
	UserSummary upgradeGuest(UUID guestId, String method, String address, String email);

	/**
	 * A guest host signing in to an account they already had: what the guest account holds moves to
	 * {@code into} (GuestAccountMerged), and the guest account is retired.
	 */
	void mergeGuest(UUID guestId, UUID into);

}
