package com.playchale.api.auth.web;

import java.time.Duration;

import com.playchale.api.shared.config.PlaychaleProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

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

	/**
	 * The session token a request carries: the phone app sends it as {@code Authorization: Bearer},
	 * the web app as this cookie. The header wins when both are there.
	 */
	static String tokenOf(HttpServletRequest request) {
		var header = request.getHeader("Authorization");
		if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
			var token = header.substring(7).strip();
			if (!token.isEmpty()) {
				return token;
			}
		}
		var cookie = WebUtils.getCookie(request, NAME);
		return cookie == null ? null : cookie.getValue();
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
