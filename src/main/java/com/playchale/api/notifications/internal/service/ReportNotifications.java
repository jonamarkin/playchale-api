package com.playchale.api.notifications.internal.service;

import java.util.Map;

import com.playchale.api.reports.api.ReportsClosed;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Whoever reported something hears that PlayChale looked at it: not what was decided, which is the
 * other person's business, but that the report wasn't lost.
 */
@Component
class ReportNotifications {

	private static final Map<String, String> WHAT = Map.of("game", "game", "message", "message", "user", "person", "venue", "venue");

	private final NotificationService notifications;

	ReportNotifications(NotificationService notifications) {
		this.notifications = notifications;
	}

	@EventListener
	void on(ReportsClosed e) {
		var what = WHAT.getOrDefault(e.subjectType(), "thing");
		for (var reporter : e.reporterIds()) {
			notifications.send(reporter, "report", "Thanks for your report",
					"PlayChale’s team looked at the %s you reported.".formatted(what), "/home", null);
		}
	}

}
