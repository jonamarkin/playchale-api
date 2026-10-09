package com.playchale.api.notifications.internal.service;

import com.playchale.api.events.api.EventActivity;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Telling people what happened in an event they're part of. Only account holders can be told. */
@Component
class EventNotifications {

	private final NotificationService notifications;

	EventNotifications(NotificationService notifications) {
		this.notifications = notifications;
	}

	@EventListener
	void on(EventActivity.CoordinatorAdded event) {
		for (var recipient : event.recipients()) {
			notifications.send(recipient, "event", "You’re running " + event.gameName(),
				"%s: you can make its draw and record its results.".formatted(event.eventName()), game(event.eventId(), event.gameId()),
				event.actorId());
		}
	}

	@EventListener
	void on(EventActivity.EntryMade event) {
		var single = event.entryName() == null;
		for (var recipient : event.recipients()) {
			notifications.send(recipient, "event", "You’re in " + event.gameName(),
				single ? event.eventName() : "%s · %s".formatted(event.eventName(), event.entryName()), game(event.eventId(), event.gameId()),
				event.actorId());
		}
	}

	@EventListener
	void on(EventActivity.DrawMade event) {
		for (var first : event.recipients()) {
			var against = first.opponent() == null ? "Open it to see where you are." : "First up: against %s%s.".formatted(first.opponent(),
				first.when() == null ? "" : ", " + first.when());
			notifications.send(first.userId(), "event", "%s: the draw is out".formatted(event.gameName()), against,
				game(event.eventId(), event.gameId()), event.actorId());
		}
	}

	@EventListener
	void on(EventActivity.ResultRecorded event) {
		for (var recipient : event.recipients()) {
			notifications.send(recipient, "event", event.gameName(), event.summary(), game(event.eventId(), event.gameId()), event.actorId());
		}
	}

	@EventListener
	void on(EventActivity.EventFinished event) {
		for (var recipient : event.recipients()) {
			notifications.send(recipient, "event", event.eventName() + " is over",
				event.winner() == null ? "See how everyone finished." : "%s won it. See how everyone finished.".formatted(event.winner()),
				"/events/%s".formatted(event.eventId()), event.actorId());
		}
	}

	private static String game(java.util.UUID eventId, java.util.UUID gameId) {
		return "/events/%s/games/%s".formatted(eventId, gameId);
	}

}
