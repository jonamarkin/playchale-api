package com.playchale.api.users.web.dto;

import java.util.List;
import java.util.Map;

import com.playchale.api.users.internal.service.ProfileChanges;
import jakarta.validation.constraints.Size;

/**
 * The web app's ProfileUpdate: every field optional.
 *
 * @param roles    positions per sport, replacing all of them: {"football": ["goalkeeper"]}
 * @param avatarSeed the face picked with Shuffle; blank goes back to the one from their id
 * @param position the old free-text position. Accepted and ignored for one release, so a web app
 *                 that's a few minutes behind the API still saves; remove it after that release.
 * @param termsVersion the Terms they agreed to (onboarding only; required there)
 * @param areas    where they usually play, main one first, up to 3; replaces {@code area}
 */
public record ProfileUpdateRequest(String name, String handle, String area,
		@Size(max = 10, message = "Pick sports from the list.") List<String> sports,
		@Size(max = 10, message = "Pick sports from the list.") Map<String, List<String>> roles, @Deprecated String position,
		String payoutPhone, String email, String avatarSeed, String country, String termsVersion,
		@Size(max = 10, message = "Pick up to 3 places.") List<String> areas) {

	public ProfileChanges toChanges() {
		return new ProfileChanges(name, handle, area, sports, roles, payoutPhone, email, avatarSeed, country, areas);
	}

}
