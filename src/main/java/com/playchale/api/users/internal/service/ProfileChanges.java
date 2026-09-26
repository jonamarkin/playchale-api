package com.playchale.api.users.internal.service;

import java.util.List;
import java.util.Map;

/**
 * What a player changed about themselves. A null field is left as it was; a blank optional field
 * (area, payout number, email) clears it, and a blank {@code avatarSeed} goes back to the face from
 * their id. {@code roles}, when given, replaces all their positions:
 * per sport, catalogue ids, main one first.
 */
public record ProfileChanges(String name, String handle, String area, List<String> sports, Map<String, List<String>> roles,
		String payoutPhone, String email, String avatarSeed) {

	public ProfileChanges(String name, String handle, String area, List<String> sports, Map<String, List<String>> roles,
			String payoutPhone, String email) {
		this(name, handle, area, sports, roles, payoutPhone, email, null);
	}
}
