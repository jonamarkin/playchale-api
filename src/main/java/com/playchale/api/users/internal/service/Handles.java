package com.playchale.api.users.internal.service;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.playchale.api.users.internal.domain.User;
import com.playchale.api.users.internal.repository.UserRepository;
import org.springframework.stereotype.Component;

/**
 * A username for someone who didn't pick one: signing up asks only for a name now, and the username
 * (their profile link) can be changed any time after. It's made from the name the way the web app
 * suggests one ("Kwame Asante" → kwame_asante), with four digits added when that's taken, and never
 * a word that would pass for PlayChale itself.
 */
@Component
class Handles {

	/** Usernames that would read as PlayChale speaking, or as a page of the app. */
	private static final Set<String> RESERVED = Set.of("playchale", "admin", "admins", "support", "help", "staff", "official",
			"officials", "team", "teams", "security", "settings", "about", "terms", "privacy", "me", "null", "undefined");

	/** Room for "_1234" within the 20 characters a username can have. */
	private static final int STEM = 15;

	private final UserRepository users;

	private final SecureRandom random = new SecureRandom();

	Handles(UserRepository users) {
		this.users = users;
	}

	/** A free username made from {@code name}, for the account {@code self} (its own counts as free). */
	String forName(String name, UUID self) {
		var base = fromName(name);
		var usable = base.length() >= 3 && !RESERVED.contains(base);
		if (usable && free(base, self)) {
			return base;
		}
		var stem = usable ? base.substring(0, Math.min(STEM, base.length())).replaceAll("_+$", "") : "player";
		for (int attempt = 0; attempt < 5; attempt++) {
			var candidate = stem + "_" + (1000 + random.nextInt(9000));
			if (free(candidate, self)) {
				return candidate;
			}
		}
		var bytes = new byte[4];
		random.nextBytes(bytes);
		return "player_" + HexFormat.of().formatHex(bytes);
	}

	/** "Kwame Asante" → "kwame_asante"; "Adwoa Ɔforiwaa" → "adwoa_oforiwaa"; letters it can't spell → "". */
	static String fromName(String name) {
		if (name == null) {
			return "";
		}
		var plain = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}", "").replace('ɔ', 'o').replace('Ɔ', 'o')
			.replace('ɛ', 'e').replace('Ɛ', 'e').toLowerCase(Locale.ROOT);
		var handle = plain.replaceAll("[^a-z0-9\\s_]", "").strip().replaceAll("[\\s_]+", "_").replaceAll("^_+|_+$", "");
		return handle.substring(0, Math.min(20, handle.length())).replaceAll("_+$", "");
	}

	private boolean free(String handle, UUID self) {
		return User.isValidHandle(handle) && users.findByHandleIgnoreCase(handle).map(owner -> owner.getId().equals(self)).orElse(true);
	}

}
