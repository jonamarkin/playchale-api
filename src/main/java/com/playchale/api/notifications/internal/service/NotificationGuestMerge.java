package com.playchale.api.notifications.internal.service;

import com.playchale.api.users.api.GuestAccountMerged;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A guest host signed in to the account they already had: what they were told as a guest host
 * (who joined, who paid) is in that account's notifications, and the phones they turned
 * notifications on for are its phones. The account's own settings stay as they were.
 */
@Component
class NotificationGuestMerge {

	private final JdbcClient jdbc;

	NotificationGuestMerge(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@EventListener
	void on(GuestAccountMerged e) {
		for (var sql : new String[] {
				"UPDATE notifications SET user_id = :into WHERE user_id = :guest",
				"UPDATE notifications SET actor_id = :into WHERE actor_id = :guest",
				"UPDATE push_subscriptions SET user_id = :into WHERE user_id = :guest",
				"DELETE FROM push_preferences WHERE user_id = :guest",
				"DELETE FROM email_preferences WHERE user_id = :guest" }) {
			jdbc.sql(sql).param("guest", e.guestId()).param("into", e.into()).update();
		}
	}

}
