package com.playchale.api.users.web;

import java.util.Optional;

import com.playchale.api.shared.security.CurrentUser;
import com.playchale.api.users.api.UserSummary;
import com.playchale.api.users.internal.service.UserProfileService;
import com.playchale.api.users.web.dto.HandleAvailability;
import com.playchale.api.users.web.dto.ProfileUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in player's own profile. */
@RestController
class MeController {

	private final UserProfileService profiles;

	MeController(UserProfileService profiles) {
		this.profiles = profiles;
	}

	/** profiles.update */
	@PatchMapping("/me")
	UserSummary update(CurrentUser me, @Valid @RequestBody ProfileUpdateRequest request) {
		return profiles.update(me.id(), request.toChanges());
	}

	/** profiles.completeOnboarding */
	@PostMapping("/me/onboarding")
	UserSummary completeOnboarding(CurrentUser me, @Valid @RequestBody ProfileUpdateRequest request) {
		return profiles.completeOnboarding(me.id(), request.toChanges());
	}

	/** profiles.deleteAccount → 204. The web app then signs out. */
	@DeleteMapping("/me")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void deleteAccount(CurrentUser me) {
		profiles.deleteAccount(me.id());
	}

	/** profiles.isHandleAvailable */
	@GetMapping("/handles/{handle}")
	HandleAvailability isHandleAvailable(@PathVariable String handle, Optional<CurrentUser> me) {
		return new HandleAvailability(profiles.isHandleAvailable(handle, me.map(CurrentUser::id)));
	}

}
