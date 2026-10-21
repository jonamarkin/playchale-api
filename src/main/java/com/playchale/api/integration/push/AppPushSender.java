package com.playchale.api.integration.push;

/**
 * Sends a notification to one phone with the PlayChale app, by the token the app registered (an Expo
 * push token). Its own interface rather than {@link PushSender}'s: a browser is reached by its
 * endpoint and keys, an app by a token.
 */
public interface AppPushSender {

	/** Whether this is a token the app's push service gives out: "ExponentPushToken[…]". */
	static boolean isToken(String token) {
		return token != null && token.length() <= 200 && token.matches("Expo(nent)?PushToken\\[[A-Za-z0-9_\\-]+]");
	}

	/** Sends it, opening {@code link} (a path in the app, e.g. "/games/…") when tapped. Never throws. */
	PushSender.Delivery send(String token, String title, String body, String link);

}
