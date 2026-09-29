package com.playchale.api.users.api;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A player as the rest of the app sees them: a read-only snapshot, never the entity. It's also the
 * JSON the web app's User type expects (webapp/app/types/domain.ts), so any module can put a player
 * straight into a response. Optional fields are left out rather than sent as null, matching
 * {@code field?: type} in TypeScript.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserSummary(UUID id, String phone, String name, String handle, String avatar, String avatarSeed, String tint, String area,
		List<String> sports, Map<String, List<String>> roles, Instant createdAt, boolean onboarded, String payoutPhone,
		String email, String signInEmail, String country, String termsVersion, Boolean google) {

	/**
	 * @param roles        positions (or athletics events) per sport, as catalogue ids, main one first:
	 *                     {"football": ["forward", "midfielder"]}. Keyed in the order of their sports; public.
	 * @param termsVersion the Terms they agreed to (a date), or null if they haven't yet; private
	 * @param google       whether they can sign in with Google; private
	 */
	public UserSummary {
		sports = List.copyOf(sports);
		roles = Collections.unmodifiableMap(new LinkedHashMap<>(roles));
		// The web app's dates are JavaScript Dates, which only go to the millisecond.
		createdAt = createdAt.truncatedTo(ChronoUnit.MILLIS);
	}

	/**
	 * The player as anyone else sees them: without their phone, payout number, emails or Terms, which
	 * the web app only ever shows to the player themselves.
	 */
	public UserSummary toPublic() {
		return new UserSummary(id, "", name, handle, avatar, avatarSeed, tint, area, sports, roles, createdAt, onboarded, null, null, null, country, null, null);
	}

	/** Everything to the player themselves; the public view to anyone else. */
	public UserSummary as(UUID viewer) {
		return id.equals(viewer) ? this : toPublic();
	}

}
