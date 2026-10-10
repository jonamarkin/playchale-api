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
			var against = first.opponent() != null ? "First up: against %s%s.".formatted(first.opponent(), first.when() == null ? "" : ", " + first.when())
					: first.when() != null ? "First up: %s.".formatted(first.when()) : "Open it to see where you are.";
			notifications.send(first.userId(), "event", (event.timesOnly() ? "%s: the times are out" : "%s: the draw is out").formatted(event.gameName()),
				against, game(event.eventId(), event.gameId()), event.actorId());
		}
	}

	@EventListener
	void on(EventActivity.ResultRecorded event) {
		for (var recipient : event.recipients()) {
			notifications.send(recipient, "event", event.gameName(), event.summary(), game(event.eventId(), event.gameId()), event.actorId());
		}
	}

	@EventListener
	void on(EventActivity.Announced event) {
		var body = event.body().length() > 180 ? event.body().substring(0, 179).strip() + "…" : event.body();
		for (var recipient : event.recipients()) {
			notifications.send(recipient, "event", event.eventName(), body, "/events/" + event.eventId(), event.actorId());
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
