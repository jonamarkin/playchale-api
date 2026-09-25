package com.playchale.api.profiles.web;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.profiles.internal.service.MatchRecord;
import com.playchale.api.profiles.internal.service.PlayerProfile;
import com.playchale.api.profiles.internal.service.PlayerProfileService;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.shared.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Player profiles, readable signed out: the web app's /p/[handle] pages are public. */
@RestController
class PlayerProfileController {

	private final PlayerProfileService profiles;

	PlayerProfileController(PlayerProfileService profiles) {
		this.profiles = profiles;
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
