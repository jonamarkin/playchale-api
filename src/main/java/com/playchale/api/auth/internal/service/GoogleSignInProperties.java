package com.playchale.api.auth.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Signing in with Google.
 *
 * @param clientId the web app's OAuth client ID from Google Cloud (public, not a secret):
 *                 PLAYCHALE_GOOGLE_CLIENT_ID. Empty: Google sign-in isn't offered
 */
@ConfigurationProperties("playchale.google")
public record GoogleSignInProperties(String clientId) {

	public GoogleSignInProperties {
		clientId = clientId == null ? "" : clientId.strip();
	}

	public boolean enabled() {
		return !clientId.isEmpty();
	}

}
