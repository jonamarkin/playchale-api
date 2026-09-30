package com.playchale.api.notifications.internal.service;

import com.playchale.api.competitions.api.CorporateEvents;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** In-app and push delivery for corporate operations; WhatsApp remains an explicit share link. */
@Component
class CorporateNotifications {

	private final NotificationService notifications;

	CorporateNotifications(NotificationService notifications) {
		this.notifications = notifications;
	}

	@EventListener
	void on(CorporateEvents.AnnouncementPublished event) {
		for (var recipient : event.recipients()) {
			notifications.send(recipient, "competition-announcement", event.title(), event.body(),
				"/competitions/%s/operations?section=communications".formatted(event.competitionId()), event.actorId());
		}
	}

	@EventListener
	void on(CorporateEvents.SchedulePublished event) {
		for (var recipient : event.recipients()) {
			notifications.send(recipient, "game-invite", event.competitionName() + ": fixtures are out",
				"Open the schedule to see your matchdays.", "/competitions/%s".formatted(event.competitionId()), event.actorId());
		}
	}

	@EventListener
	void on(CorporateEvents.FixtureMoved event) {
		for (var recipient : event.recipients()) {
			notifications.send(recipient, "game-reminder", event.competitionName() + ": fixture moved",
				event.message(), "/games/%s".formatted(event.fixtureId()), event.actorId());
		}
	}
}
