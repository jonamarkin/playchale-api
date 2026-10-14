package com.playchale.api.auth.internal.service;

import java.util.stream.Stream;

import com.playchale.api.auth.internal.repository.SessionRepository;
import com.playchale.api.auth.internal.repository.SignInCodeRepository;
import com.playchale.api.users.api.AccountDeleted;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A deleted account is signed out everywhere, and its phone's or email's sign-in codes go too, as
 * does a guest host's manage link.
 */
@Component
class AuthAccountDeletion {

	private final SessionRepository sessions;

	private final SignInCodeRepository codes;

	private final JdbcClient jdbc;

	AuthAccountDeletion(SessionRepository sessions, SignInCodeRepository codes, JdbcClient jdbc) {
		this.sessions = sessions;
		this.codes = codes;
		this.jdbc = jdbc;
	}

	@EventListener
	void on(AccountDeleted e) {
		sessions.deleteAllOf(e.userId());
		jdbc.sql("DELETE FROM host_keys WHERE user_id = :user").param("user", e.userId()).update();
		var recipients = Stream.of(e.phone(), e.signInEmail()).filter(r -> r != null && !r.isBlank()).toList();
		if (!recipients.isEmpty()) {
			codes.deleteAllTo(recipients);
		}
	}

}
