package com.playchale.api.profiles.web;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.profiles.internal.service.Leaderboard;
import com.playchale.api.profiles.internal.service.MatchRecord;
import com.playchale.api.profiles.internal.service.PlayerProfile;
import com.playchale.api.profiles.internal.service.PlayerProfileService;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.shared.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Player profiles, readable signed out: the web app's /p/[handle] pages are public. */
@RestController
class PlayerProfileController {

	private final PlayerProfileService profiles;

	private final Leaderboard leaderboard;

	PlayerProfileController(PlayerProfileService profiles, Leaderboard leaderboard) {
		this.profiles = profiles;
		this.leaderboard = leaderboard;
	}

	/**
	 * profiles.leaderboard: who is top, filtered. Public, as profiles are.
	 *
	 * @param metric goals, assists, points, wins or games; anything else falls back to goals
	 * @param period month, year or all
	 */
	@GetMapping("/leaderboard")
	List<Leaderboard.Standing> leaderboard(@RequestParam(required = false) String sport, @RequestParam(required = false) String country,
			@RequestParam(required = false) String area, @RequestParam(required = false) String period,
			@RequestParam(required = false) String metric, @RequestParam(defaultValue = "20") int limit) {
		return leaderboard.top(sport, country, area, period(period), metric, limit);
	}

	/** profiles.leaderboardAreas: the places that actually have played games, for the filter. */
	@GetMapping("/leaderboard/areas")
	List<String> leaderboardAreas(@RequestParam(required = false) String country) {
		return leaderboard.areas(country);
	}

	private static Leaderboard.Period period(String value) {
		if (value == null || value.isBlank()) {
			return Leaderboard.Period.YEAR;
		}
		try {
			return Leaderboard.Period.valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
		}
		catch (IllegalArgumentException unknown) {
			return Leaderboard.Period.YEAR;
		}
	}

	/** profiles.get */
	@GetMapping("/users/{id}/profile")
	PlayerProfile get(@PathVariable UUID id, Optional<CurrentUser> me) {
		return profiles.get(id, me.map(CurrentUser::id));
	}

	/** profiles.getByHandle: 404 when no one has it, which the web app reads as null. */
	@GetMapping("/profiles/{handle}")
	PlayerProfile getByHandle(@PathVariable String handle, Optional<CurrentUser> me) {
		return profiles.getByHandle(handle, me.map(CurrentUser::id))
			.orElseThrow(() -> BusinessException.notFound("That player could not be found."));
	}

	/** profiles.history */
	@GetMapping("/users/{id}/history")
	List<MatchRecord> history(@PathVariable UUID id) {
		return profiles.history(id);
	}

}
