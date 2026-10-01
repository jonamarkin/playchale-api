package com.playchale.api.notifications.web;

import java.util.List;
import java.util.UUID;

import com.playchale.api.notifications.internal.service.EmailService;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.shared.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Email settings, one endpoint per method of {@code email} in the web app's contract. */
@RestController
class EmailController {

	private final EmailService email;

	EmailController(EmailService email) {
		this.email = email;
	}

	/** {"muted": [...]} | {"marketing": true} | {"digest": "weekly"} — any one of the three. */
	record Change(List<String> muted, Boolean marketing, String digest) {
	}

	/** {"token": "..."} */
	record Token(String token) {
	}

	/** @param name whose settings these were, so the page can say "You're unsubscribed, Kwame" */
	record Unsubscribed(String name) {
	}

	/** email.settings */
	@GetMapping("/me/email")
	EmailService.Settings settings(CurrentUser me) {
		return email.settings(me.id());
	}

	/** email.update */
	@PatchMapping("/me/email")
	EmailService.Settings update(CurrentUser me, @RequestBody Change change) {
		var settings = email.settings(me.id());
		if (change.muted() != null) {
			settings = email.mute(me.id(), change.muted());
		}
		if (change.marketing() != null) {
			settings = email.setMarketing(me.id(), change.marketing());
		}
		if (change.digest() != null) {
			settings = email.setDigest(me.id(), change.digest());
		}
		return settings;
	}

	/**
	 * email.unsubscribe: the link in an email's footer, with nobody signed in. A token that means
	 * nothing is answered the same way as one that worked — there is nothing to learn by trying
	 * others, and someone whose link has been rotated shouldn't be shown an error they can't act on.
	 */
	@PostMapping("/email/unsubscribe")
	Unsubscribed unsubscribe(@RequestBody Token request) {
		return new Unsubscribed(email.unsubscribe(token(request)).orElse(""));
	}

	private static UUID token(Token request) {
		try {
			return UUID.fromString(request.token() == null ? "" : request.token().trim());
		}
		catch (IllegalArgumentException e) {
			throw BusinessException.invalid("That link doesn’t look right. Open the email again, or change it in your settings.");
		}
	}

}
