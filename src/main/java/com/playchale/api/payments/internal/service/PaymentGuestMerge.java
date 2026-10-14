package com.playchale.api.payments.internal.service;

import com.playchale.api.users.api.GuestAccountMerged;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A guest host signed in to the account they already had: the cash they marked as paid to them is
 * on that account's statement now.
 */
@Component
class PaymentGuestMerge {

	private final JdbcClient jdbc;

	PaymentGuestMerge(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@EventListener
	void on(GuestAccountMerged e) {
		for (var sql : new String[] {
				"UPDATE payments SET user_id = :into WHERE user_id = :guest",
				"UPDATE movements SET user_id = :into WHERE user_id = :guest",
				"UPDATE movements SET counterparty_id = :into WHERE counterparty_id = :guest" }) {
			jdbc.sql(sql).param("guest", e.guestId()).param("into", e.into()).update();
		}
	}

}
