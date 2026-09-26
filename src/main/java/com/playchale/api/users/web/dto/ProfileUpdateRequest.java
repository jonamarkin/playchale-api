package com.playchale.api.users.web.dto;

import java.util.List;
import java.util.Map;

import com.playchale.api.users.internal.service.ProfileChanges;
import jakarta.validation.constraints.Size;

/**
 * The web app's ProfileUpdate: every field optional.
 *
 * @param roles    positions per sport, replacing all of them: {"football": ["goalkeeper"]}
 * @param position the old free-text position. Accepted and ignored for one release, so a web app
 *                 that's a few minutes behind the API still saves; remove it after that release.
 */
public record ProfileUpdateRequest(String name, String handle, String area,
		@Size(max = 10, message = "Pick sports from the list.") List<String> sports,
		@Size(max = 10, message = "Pick sports from the list.") Map<String, List<String>> roles, @Deprecated String position,
		String payoutPhone, String email) {

	public ProfileChanges toChanges() {
		return new ProfileChanges(name, handle, area, sports, roles, payoutPhone, email);
	}

}
