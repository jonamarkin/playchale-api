package com.playchale.api.notifications.web;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.playchale.api.notifications.internal.service.PushService;
import com.playchale.api.shared.security.CurrentUser;
import com.playchale.api.shared.security.GuestHostsAllowed;
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

	/**
	 * {"endpoint", "keys": {"p256dh", "auth"}}: a browser's PushSubscription, as it serialises itself.
	 * Browsers add fields of their own (expirationTime, say), which are ignored.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	record Subscription(String endpoint, Keys keys) {

		@JsonIgnoreProperties(ignoreUnknown = true)
		record Keys(String p256dh, String auth) {
		}

	}

	/** {"endpoint"} */
	record Endpoint(String endpoint) {
	}

	/** {"token": "ExponentPushToken[…]", "platform": "android" | "ios"}: a phone with the app. */
	record Device(String token, String platform) {
	}

	/** {"muted": ["payments", ...]} */
	record Muted(List<String> muted) {
	}

	/** push.settings */
	@GetMapping("/me/push")
	@GuestHostsAllowed
	PushService.Settings settings(CurrentUser me) {
		return push.settings(me.id());
	}

	/** push.subscribe */
	@PostMapping("/me/push/subscriptions")
	@GuestHostsAllowed
	PushService.Settings subscribe(CurrentUser me, @RequestBody Subscription request) {
		var keys = request.keys() == null ? new Subscription.Keys(null, null) : request.keys();
		return push.subscribe(me.id(), request.endpoint(), keys.p256dh(), keys.auth());
	}

	/** push.unsubscribe */
	@PostMapping("/me/push/unsubscriptions")
	@GuestHostsAllowed
	PushService.Settings unsubscribe(CurrentUser me, @RequestBody Endpoint request) {
		return push.unsubscribe(me.id(), request.endpoint());
	}

	/** push.registerDevice: from the phone app. */
	@PostMapping("/me/push/devices")
	PushService.Settings registerDevice(CurrentUser me, @RequestBody Device request) {
		return push.registerDevice(me.id(), request.token(), request.platform());
	}

	/** push.forgetDevice: from the phone app, on signing out or turning them off. */
	@PostMapping("/me/push/device-removals")
	PushService.Settings forgetDevice(CurrentUser me, @RequestBody Device request) {
		return push.forgetDevice(me.id(), request.token());
	}

	/** push.mute */
	@PatchMapping("/me/push")
	@GuestHostsAllowed
	PushService.Settings mute(CurrentUser me, @RequestBody Muted request) {
		return push.mute(me.id(), request.muted());
	}

}
