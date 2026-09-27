package com.playchale.api.notifications.internal.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends each notification on to phones once it's committed: never for a change that was rolled
 * back, and never holding up the request that caused it.
 */
@Component
class PushDispatch {

	private final PushService push;

	private final PushQueue queue;

	PushDispatch(PushService push, PushQueue queue) {
		this.push = push;
		this.queue = queue;
	}

	@TransactionalEventListener(fallbackExecution = true)
	void on(NotificationSaved saved) {
		queue.submit(() -> push.deliver(saved));
	}

}
