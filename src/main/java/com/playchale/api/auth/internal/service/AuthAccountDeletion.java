package com.playchale.api.auth.internal.service;

import java.util.stream.Stream;

import com.playchale.api.auth.internal.repository.SessionRepository;
import com.playchale.api.auth.internal.repository.SignInCodeRepository;
import com.playchale.api.users.api.AccountDeleted;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** A deleted account is signed out everywhere, and its phone's or email's sign-in codes go too. */
@Component
class AuthAccountDeletion {

	private final SessionRepository sessions;

	private final SignInCodeRepository codes;

	AuthAccountDeletion(SessionRepository sessions, SignInCodeRepository codes) {
		this.sessions = sessions;
		this.codes = codes;
	}

	@EventListener
	void on(AccountDeleted e) {
		sessions.deleteAllOf(e.userId());
		var recipients = Stream.of(e.phone(), e.signInEmail()).filter(r -> r != null && !r.isBlank()).toList();
		if (!recipients.isEmpty()) {
			codes.deleteAllTo(recipients);
		}
	}

}
