package com.playchale.api.teams.internal.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.playchale.api.teams.api.TeamLeagues.TeamLeague;
import com.playchale.api.users.api.UserSummary;

/**
 * A team as the web app's TeamProfile: its people, leagues and, for its captain, the join link and
 * the requests waiting on them.
 *
 * @param joinToken "" for anyone but the captain
 * @param requests  only for the captain
 * @param requested whether the viewer has a request waiting on this team
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TeamResponse(UUID id, String name, String tint, UUID captainId, UserSummary captain, List<UUID> memberIds,
		List<UserSummary> members, String joinToken, Instant createdAt, List<TeamLeague> leagues, List<RequestView> requests,
		boolean requested) {

	public record RequestView(UUID id, UUID teamId, UUID userId, String status, Instant createdAt, UserSummary user) {
	}

}
