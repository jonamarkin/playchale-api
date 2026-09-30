package com.playchale.api.competitions.api;

import java.util.List;
import java.util.UUID;

/** Events whose delivery belongs to the notifications module. */
public final class CorporateEvents {

	private CorporateEvents() {
	}

	public record AnnouncementPublished(UUID competitionId, String competitionName, UUID announcementId,
			String title, String body, List<UUID> recipients, UUID actorId) {
	}

	public record SchedulePublished(UUID competitionId, String competitionName, List<UUID> recipients, UUID actorId) {
	}

	public record FixtureMoved(UUID competitionId, String competitionName, UUID fixtureId, List<UUID> recipients,
			String message, UUID actorId) {
	}
}
