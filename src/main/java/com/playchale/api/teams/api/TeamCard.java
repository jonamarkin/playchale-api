package com.playchale.api.teams.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A team in brief, for other modules.
 *
 * @param memberIds   who is in it, earliest first. The captain is only here if they play.
 * @param logoVersion when its crest last changed, or null if it has none
 */
public record TeamCard(UUID id, String name, UUID captainId, List<UUID> memberIds, String tint, Instant createdAt, Long logoVersion) {

	public TeamCard {
		memberIds = List.copyOf(memberIds);
	}

	public boolean has(UUID userId) {
		return memberIds.contains(userId);
	}

}
