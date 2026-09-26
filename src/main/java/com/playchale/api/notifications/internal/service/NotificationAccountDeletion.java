package com.playchale.api.notifications.internal.service;

import com.playchale.api.notifications.internal.repository.NotificationRepository;
import com.playchale.api.users.api.AccountDeleted;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** A deleted account's notifications go with it. (Ones they caused for others stay, from a "deleted player".) */
@Component
class NotificationAccountDeletion {

	private final NotificationRepository notifications;

	NotificationAccountDeletion(NotificationRepository notifications) {
		this.notifications = notifications;
	}

	@EventListener
	void on(AccountDeleted e) {
		notifications.deleteAllFor(e.userId());
	}

}
