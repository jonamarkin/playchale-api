package com.playchale.api.auth.web;

import java.time.Duration;

import com.playchale.api.shared.config.PlaychaleProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The session cookie. The browser sends it automatically; HttpOnly keeps page scripts from reading
 * it, so a script injected into the page can't steal it.
 */
@Component
class SessionCookies {

	static final String NAME = "playchale_session";

	private final boolean secure;

	SessionCookies(PlaychaleProperties properties) {
		this.secure = properties.secureCookies();
	}

	ResponseCookie issue(String token, Duration validFor) {
		return build(token, validFor);
	}

	ResponseCookie clear() {
		return build("", Duration.ZERO);
	}

	private ResponseCookie build(String value, Duration maxAge) {
		return ResponseCookie.from(NAME, value)
			.path("/")
			.maxAge(maxAge)
			.httpOnly(true)
			// Secure: only sent over HTTPS. Off only on a laptop, where the API runs on plain http.
			.secure(secure)
			.sameSite("Lax")
			.build();
	}

}
