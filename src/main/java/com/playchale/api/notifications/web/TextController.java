package com.playchale.api.notifications.web;

import com.playchale.api.notifications.internal.service.TextSettings;
import com.playchale.api.shared.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Text settings, one endpoint per method of {@code texts} in the web app's contract. */
@RestController
class TextController {

	private final TextSettings texts;

	TextController(TextSettings texts) {
		this.texts = texts;
	}

	/** {"reminders": false} */
	record Change(Boolean reminders) {
	}

	/** texts.settings */
	@GetMapping("/me/texts")
	TextSettings.Settings settings(CurrentUser me) {
		return texts.settings(me.id());
	}

	/** texts.update */
	@PatchMapping("/me/texts")
	TextSettings.Settings update(CurrentUser me, @RequestBody Change change) {
		return change.reminders() == null ? texts.settings(me.id()) : texts.setReminders(me.id(), change.reminders());
	}

}
