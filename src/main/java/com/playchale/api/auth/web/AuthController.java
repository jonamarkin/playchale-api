package com.playchale.api.auth.web;

import java.util.Optional;

import com.playchale.api.auth.api.CurrentUser;
import com.playchale.api.auth.internal.service.AuthService;
import com.playchale.api.auth.web.dto.CodeResponse;
import com.playchale.api.auth.web.dto.RequestCodeRequest;
import com.playchale.api.auth.web.dto.SignInRequest;
import com.playchale.api.users.api.UserSummary;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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

	private final AuthService auth;

	private final SessionCookies cookies;

	AuthController(AuthService auth, SessionCookies cookies) {
		this.auth = auth;
		this.cookies = cookies;
	}

	/** auth.requestOtp */
	@PostMapping("/codes")
	CodeResponse requestCode(@Valid @RequestBody RequestCodeRequest request) {
		return new CodeResponse(auth.requestCode(request.phone()).orElse(null));
	}

	/** auth.verifyOtp: the signed-in player, plus the session cookie. */
	@PostMapping("/sessions")
	ResponseEntity<UserSummary> signIn(@Valid @RequestBody SignInRequest request) {
		var signedIn = auth.signIn(request.phone(), request.code());
		return ResponseEntity.ok()
			.header(HttpHeaders.SET_COOKIE, cookies.issue(signedIn.token(), signedIn.validFor()).toString())
			.body(signedIn.user());
	}

	/**
	 * auth.currentUser: the signed-in player, or null. Not being signed in is an answer, not an error.
	 * Spring sends an empty body when a handler returns null, which a client can't parse as JSON, so
	 * "no one" is written out as the JSON literal null.
	 */
	@GetMapping("/session")
	ResponseEntity<?> currentUser(Optional<CurrentUser> me) {
		return me.flatMap(user -> auth.user(user.id()))
			.<ResponseEntity<?>>map(ResponseEntity::ok)
			.orElseGet(() -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("null"));
	}

	/** auth.signOut: 204, and the cookie is cleared. */
	@DeleteMapping("/session")
	ResponseEntity<Void> signOut(@CookieValue(name = SessionCookies.NAME, required = false) String token) {
		auth.signOut(token);
		return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear().toString()).build();
	}

}
