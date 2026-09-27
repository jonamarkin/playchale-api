package com.playchale.api.integration.push;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Web Push settings: the server's VAPID key pair, which identifies PlayChale to the browsers' push
 * services. Generate one with {@code npx web-push generate-vapid-keys} and set both keys; leave them
 * empty and phone notifications are simply off (the in-app list still works).
 *
 * @param vapidPublicKey  base64url, the 65-byte uncompressed P-256 point the web app subscribes with
 * @param vapidPrivateKey base64url, the 32-byte private scalar. A secret: only ever on the server
 * @param subject         who push services can contact about our messages
 */
@ConfigurationProperties("playchale.push")
public record PushProperties(String vapidPublicKey, String vapidPrivateKey, @DefaultValue("mailto:alert@playchale.com") String subject) {

	public boolean configured() {
		return vapidPublicKey != null && !vapidPublicKey.isBlank() && vapidPrivateKey != null && !vapidPrivateKey.isBlank();
	}

}
