package com.playchale.api.users.web.dto;

import java.util.List;

import com.playchale.api.users.internal.service.ProfileChanges;
import jakarta.validation.constraints.Size;

/** The web app's ProfileUpdate: every field optional. */
public record ProfileUpdateRequest(String name, String handle, String area,
		@Size(max = 10, message = "Pick sports from the list.") List<String> sports, String position,
		String payoutPhone, String email) {

	public ProfileChanges toChanges() {
		return new ProfileChanges(name, handle, area, sports, position, payoutPhone, email);
	}

}
