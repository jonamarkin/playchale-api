package com.playchale.api.users;

import java.time.temporal.ChronoUnit;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A player as the web app's User type expects it (webapp/app/types/domain.ts). Optional fields are
 * left out rather than sent as null, matching {@code field?: type} in TypeScript. The entity is
 * never sent as JSON itself, so the table and the API can change independently.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserJson(String id, String phone, String name, String handle, String avatar, String tint, String area,
		List<String> sports, String position, String createdAt, boolean onboarded, String payoutPhone) {

	public static UserJson of(User u) {
		return new UserJson(u.getId().toString(), u.getPhone(), u.getName(), u.getHandle(), u.getAvatarUrl(), u.getTint(),
				u.getArea(), List.copyOf(u.getSports()), u.getPosition(),
				u.getCreatedAt().truncatedTo(ChronoUnit.MILLIS).toString(), u.isOnboarded(), u.getPayoutPhone());
	}

}
