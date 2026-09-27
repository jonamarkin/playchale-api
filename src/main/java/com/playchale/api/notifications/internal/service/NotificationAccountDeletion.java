package com.playchale.api.notifications.internal.service;

import com.playchale.api.notifications.internal.repository.NotificationRepository;
import com.playchale.api.notifications.internal.repository.PushPreferenceRepository;
import com.playchale.api.notifications.internal.repository.PushSubscriptionRepository;
import com.playchale.api.users.api.AccountDeleted;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A deleted account's notifications go with it, and so do its phones and settings: nothing more is
 * sent to them. (Ones they caused for others stay, from a "deleted player".)
 */
@Component
class NotificationAccountDeletion {

	private final NotificationRepository notifications;

	private final PushSubscriptionRepository phones;

	private final PushPreferenceRepository preferences;

	NotificationAccountDeletion(NotificationRepository notifications, PushSubscriptionRepository phones, PushPreferenceRepository preferences) {
		this.notifications = notifications;
		this.phones = phones;
		this.preferences = preferences;
	}

	@EventListener
	void on(AccountDeleted e) {
		notifications.deleteAllFor(e.userId());
		phones.deleteAllFor(e.userId());
		preferences.deleteById(e.userId());
	}

}
