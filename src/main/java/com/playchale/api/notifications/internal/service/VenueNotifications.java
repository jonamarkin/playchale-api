package com.playchale.api.notifications.internal.service;

import com.playchale.api.venues.api.VenueReviewed;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A venue's owner hears what PlayChale's staff decided about it: live, or what to change first.
 * Never kept off their phone: until it's live nobody can book it.
 */
@Component
class VenueNotifications {

	private final NotificationService notifications;

	VenueNotifications(NotificationService notifications) {
		this.notifications = notifications;
	}

	@EventListener
	void on(VenueReviewed e) {
		if (e.approved()) {
			notifications.send(e.ownerId(), "venue-review", "%s is live".formatted(e.name()),
					"Players can find it on PlayChale and book its pitches now.", "/venues/%s/manage".formatted(e.venueId()), null);
			return;
		}
		var title = e.wasLive() ? "%s is off PlayChale for now".formatted(e.name()) : "%s needs a change".formatted(e.name());
		var body = e.wasLive() ? "Players can’t find or book it until you change it. PlayChale’s note: “%s”".formatted(e.note())
				: "Change it and we’ll look again. PlayChale’s note: “%s”".formatted(e.note());
		notifications.send(e.ownerId(), "venue-review", title, body, "/me/venues", null);
	}

}
