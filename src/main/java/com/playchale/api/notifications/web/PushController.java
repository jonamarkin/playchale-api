package com.playchale.api.notifications.web;

import java.util.List;

import com.playchale.api.notifications.internal.service.PushService;
import com.playchale.api.shared.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Phone notifications, one endpoint per method of {@code push} in the web app's contract. */
@RestController
class PushController {

	private final PushService push;

	PushController(PushService push) {
		this.push = push;
	}

	/** {"endpoint", "keys": {"p256dh", "auth"}}: a browser's PushSubscription, as it serialises itself. */
	record Subscription(String endpoint, Keys keys) {

		record Keys(String p256dh, String auth) {
		}

	}

	/** {"endpoint"} */
	record Endpoint(String endpoint) {
	}

	/** {"muted": ["payments", ...]} */
	record Muted(List<String> muted) {
	}

	/** push.settings */
	@GetMapping("/me/push")
	PushService.Settings settings(CurrentUser me) {
		return push.settings(me.id());
	}

	/** push.subscribe */
	@PostMapping("/me/push/subscriptions")
	PushService.Settings subscribe(CurrentUser me, @RequestBody Subscription request) {
		var keys = request.keys() == null ? new Subscription.Keys(null, null) : request.keys();
		return push.subscribe(me.id(), request.endpoint(), keys.p256dh(), keys.auth());
	}

	/** push.unsubscribe */
	@PostMapping("/me/push/unsubscriptions")
	PushService.Settings unsubscribe(CurrentUser me, @RequestBody Endpoint request) {
		return push.unsubscribe(me.id(), request.endpoint());
	}

	/** push.mute */
	@PatchMapping("/me/push")
	PushService.Settings mute(CurrentUser me, @RequestBody Muted request) {
		return push.mute(me.id(), request.muted());
	}

}
