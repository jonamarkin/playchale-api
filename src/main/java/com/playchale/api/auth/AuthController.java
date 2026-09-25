package com.playchale.api.auth;

import java.time.Duration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.playchale.api.config.PlaychaleProperties;
import com.playchale.api.users.UserJson;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in endpoints, one per method of {@code auth} in the web app's contract
 * (webapp/app/services/api.ts).
 */
@RestController
@RequestMapping("/auth")
class AuthController {

	/**
	 * The session lives in a cookie the browser sends automatically. HttpOnly keeps page scripts from
	 * reading it, so a script injected into the page can't steal it.
	 */
	static final String SESSION_COOKIE = "playchale_session";

	record CodeRequest(String phone) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record CodeResponse(String demoCode) {
	}

	record SignInRequest(String phone, String code) {
	}

	private final AuthService auth;

	private final PlaychaleProperties properties;

	AuthController(AuthService auth, PlaychaleProperties properties) {
		this.auth = auth;
		this.properties = properties;
	}

	/** auth.requestOtp: {"phone"} → {"demoCode"?} */
	@PostMapping("/codes")
	CodeResponse requestCode(@RequestBody CodeRequest request) {
		return new CodeResponse(auth.requestCode(request.phone()).orElse(null));
	}

	/** auth.verifyOtp: {"phone", "code"} → the signed-in user, plus the session cookie. */
	@PostMapping("/sessions")
	ResponseEntity<UserJson> signIn(@RequestBody SignInRequest request) {
		var signedIn = auth.verify(request.phone(), request.code());
		return ResponseEntity.ok()
			.header(HttpHeaders.SET_COOKIE, cookie(signedIn.token(), AuthService.SESSION_LIFETIME).toString())
			.body(UserJson.of(signedIn.user()));
	}

	/**
	 * auth.currentUser: the signed-in user, or null. Not being signed in is an answer, not an error.
	 * Spring sends an empty body when a handler returns null, which a client can't parse as JSON, so
	 * "no one" is written out as the JSON literal null.
	 */
	@GetMapping("/session")
	ResponseEntity<?> currentUser(@CookieValue(name = SESSION_COOKIE, required = false) String token) {
		return auth.currentUser(token)
			.<ResponseEntity<?>>map(user -> ResponseEntity.ok(UserJson.of(user)))
			.orElseGet(() -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("null"));
	}

	/** auth.signOut → 204, and the cookie is cleared. */
	@DeleteMapping("/session")
	ResponseEntity<Void> signOut(@CookieValue(name = SESSION_COOKIE, required = false) String token) {
		auth.signOut(token);
		return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
	}

	private ResponseCookie cookie(String value, Duration maxAge) {
		return ResponseCookie.from(SESSION_COOKIE, value)
			.path("/")
			.maxAge(maxAge)
			.httpOnly(true)
			// Secure: only sent over HTTPS. Off locally, where the API runs on plain http.
			.secure(properties.isProduction())
			.sameSite("Lax")
			.build();
	}

}
