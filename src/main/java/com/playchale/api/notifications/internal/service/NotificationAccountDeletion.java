package com.playchale.api.notifications.internal.service;

import com.playchale.api.notifications.internal.repository.EmailPreferenceRepository;
import com.playchale.api.notifications.internal.repository.NotificationRepository;
import com.playchale.api.notifications.internal.repository.PushPreferenceRepository;
import com.playchale.api.notifications.internal.repository.PushSubscriptionRepository;
import com.playchale.api.users.api.AccountDeleted;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A deleted account's notifications go with it, and so do its phones and settings: nothing more is
 * sent to them. (Ones they caused for others stay, from a "deleted player".)
 *
 * <p>The record of emails sent to them goes too. A deleted account's row is kept and anonymised so
 * other people's games still add up, which means nothing cascades on its own: mailout_recipients
 * holds an address, so it is deleted here rather than left behind as a list of who was emailed what.
 */
@Component
class NotificationAccountDeletion {

	private final NotificationRepository notifications;

	private final PushSubscriptionRepository phones;

	private final PushPreferenceRepository preferences;

	private final EmailPreferenceRepository emailPreferences;

	private final JdbcClient jdbc;

	NotificationAccountDeletion(NotificationRepository notifications, PushSubscriptionRepository phones, PushPreferenceRepository preferences,
			EmailPreferenceRepository emailPreferences, JdbcClient jdbc) {
		this.notifications = notifications;
		this.phones = phones;
		this.preferences = preferences;
		this.emailPreferences = emailPreferences;
		this.jdbc = jdbc;
	}

	@EventListener
	void on(AccountDeleted e) {
		notifications.deleteAllFor(e.userId());
		phones.deleteAllFor(e.userId());
		preferences.deleteById(e.userId());
		emailPreferences.deleteById(e.userId());
		jdbc.sql("DELETE FROM mailout_recipients WHERE user_id = :user").param("user", e.userId()).update();
	}

}
