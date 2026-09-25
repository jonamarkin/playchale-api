package com.playchale.api.users.api;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A player as the rest of the app sees them: a read-only snapshot, never the entity. It's also the
 * JSON the web app's User type expects (webapp/app/types/domain.ts), so any module can put a player
 * straight into a response. Optional fields are left out rather than sent as null, matching
 * {@code field?: type} in TypeScript.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserSummary(UUID id, String phone, String name, String handle, String avatar, String tint, String area,
		List<String> sports, String position, Instant createdAt, boolean onboarded, String payoutPhone) {

	public UserSummary {
		sports = List.copyOf(sports);
		// The web app's dates are JavaScript Dates, which only go to the millisecond.
		createdAt = createdAt.truncatedTo(ChronoUnit.MILLIS);
	}

}
