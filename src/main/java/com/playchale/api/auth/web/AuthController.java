package com.playchale.api.auth.web;

import java.util.Map;
import java.util.Optional;

import com.playchale.api.auth.internal.service.AuthService;
import com.playchale.api.auth.internal.service.GuestHosts;
import com.playchale.api.auth.web.dto.CodeResponse;
import com.playchale.api.auth.web.dto.RequestCodeRequest;
import com.playchale.api.auth.web.dto.SignInRequest;
import com.playchale.api.shared.security.CurrentUser;
import com.playchale.api.shared.security.GuestHostsAllowed;
import com.playchale.api.users.api.UserSummary;
import jakarta.servlet.http.HttpServletRequest;
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

	private final GuestHosts guests;

	private final SessionCookies cookies;

	AuthController(AuthService auth, GuestHosts guests, SessionCookies cookies) {
		this.auth = auth;
		this.guests = guests;
		this.cookies = cookies;
	}

	/** auth.options: which ways of signing in are set up. */
	@GetMapping("/options")
	AuthService.Options options() {
		return auth.options();
	}

	/** auth.requestOtp (a phone) and auth.requestEmailCode (an email) */
	@PostMapping("/codes")
	CodeResponse requestCode(@Valid @RequestBody RequestCodeRequest request, HttpServletRequest http) {
		// Behind the proxy, Tomcat takes the client's address from X-Forwarded-For (see application.yml).
		return new CodeResponse(auth.requestCode(request.phone(), request.email(), http.getRemoteAddr()).orElse(null));
	}

	/** auth.verifyOtp and auth.verifyEmailCode: the signed-in player, plus the session cookie. */
	@PostMapping("/sessions")
	ResponseEntity<UserSummary> signIn(@Valid @RequestBody SignInRequest request,
			@CookieValue(name = SessionCookies.NAME, required = false) String current) {
		// On a device hosting as a guest, signing in makes that guest an account (or moves its games to theirs).
		var signedIn = auth.signIn(request.phone(), request.email(), request.code(), request.country(), current);
		return ResponseEntity.ok()
			.header(HttpHeaders.SET_COOKIE, cookies.issue(signedIn.token(), signedIn.validFor()).toString())
			.body(signedIn.user());
	}

	record GoogleSignInRequest(String credential, String country) {
	}

	/** auth.signInWithGoogle: the ID token from Google's button; the signed-in player, plus the session cookie. */
	@PostMapping("/google")
	ResponseEntity<UserSummary> signInWithGoogle(@RequestBody GoogleSignInRequest request,
			@CookieValue(name = SessionCookies.NAME, required = false) String current) {
		var signedIn = auth.signInWithGoogle(request.credential(), request.country(), current);
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
	@GuestHostsAllowed
	ResponseEntity<?> currentUser(Optional<CurrentUser> me) {
		return me.flatMap(user -> auth.user(user.id()))
			.<ResponseEntity<?>>map(ResponseEntity::ok)
			.orElseGet(() -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("null"));
	}

	record GuestHostRequest(String name, String phone, String country, String termsVersion) {
	}

	/** A guest host's account, as made: with their manage key, given only now. */
	record GuestHostResponse(UserSummary user, String manageKey) {
	}

	/**
	 * auth.becomeGuestHost: hosting a game without an account. A name and a number for the place's
	 * country, and the Terms; the session cookie, and the manage key, a private link back.
	 */
	@PostMapping("/guest-hosts")
	ResponseEntity<GuestHostResponse> becomeGuestHost(@RequestBody GuestHostRequest request, HttpServletRequest http,
			@CookieValue(name = SessionCookies.NAME, required = false) String current) {
		var started = guests.start(request.name(), request.phone(), request.country(), request.termsVersion(), http.getRemoteAddr(), current);
		return ResponseEntity.status(201)
			.header(HttpHeaders.SET_COOKIE, cookies.issue(started.token(), started.validFor()).toString())
			.body(new GuestHostResponse(started.user(), started.manageKey()));
	}

	record HostKeyRequest(String key) {
	}

	/** auth.openHostKey: a guest host's manage link, opened on another browser; the session cookie there. */
	@PostMapping("/host-keys/sessions")
	ResponseEntity<UserSummary> openHostKey(@RequestBody HostKeyRequest request, HttpServletRequest http) {
		var signedIn = guests.open(request.key(), http.getRemoteAddr());
		return ResponseEntity.ok()
			.header(HttpHeaders.SET_COOKIE, cookies.issue(signedIn.token(), signedIn.validFor()).toString())
			.body(signedIn.user());
	}

	/** auth.newHostKey: a new manage link for the guest host; the old one stops working. */
	@PostMapping("/host-keys")
	@GuestHostsAllowed
	Map<String, String> newHostKey(CurrentUser me) {
		return Map.of("manageKey", guests.replaceKey(me.id()));
	}

	/** auth.signOut: 204, and the cookie is cleared. */
	@DeleteMapping("/session")
	ResponseEntity<Void> signOut(@CookieValue(name = SessionCookies.NAME, required = false) String token) {
		auth.signOut(token);
		return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear().toString()).build();
	}

}
