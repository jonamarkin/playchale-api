package com.playchale.api.integration.push;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * One browser's push subscription, as the web app hands it over.
 *
 * @param endpoint the push service URL for this browser
 * @param p256dh   base64url, the browser's public key for encrypting to it
 * @param auth     base64url, the browser's 16-byte auth secret
 */
public record PushTarget(String endpoint, String p256dh, String auth) {

	/**
	 * The browsers' push services. The endpoint comes from the browser, so the server only ever sends
	 * to these: never to an address someone typed in, which could be inside our own network.
	 */
	private static final List<String> SERVICES = List.of("fcm.googleapis.com", "updates.push.services.mozilla.com", "push.apple.com",
			"notify.windows.com");

	/** Keys of the right shape: a 65-byte uncompressed P-256 public key and a 16-byte auth secret. */
	public boolean wellFormed() {
		try {
			var key = WebPushCrypto.base64url(p256dh);
			return key.length == 65 && key[0] == 4 && WebPushCrypto.base64url(auth).length == 16;
		}
		catch (IllegalArgumentException | NullPointerException e) {
			return false;
		}
	}

	/** An https endpoint on one of the browsers' push services. */
	public boolean trusted() {
		try {
			var uri = URI.create(endpoint);
			var host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
			return "https".equals(uri.getScheme()) && uri.getUserInfo() == null
					&& SERVICES.stream().anyMatch(s -> host.equals(s) || host.endsWith("." + s));
		}
		catch (IllegalArgumentException | NullPointerException e) {
			return false;
		}
	}

}
