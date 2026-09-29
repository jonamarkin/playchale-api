package com.playchale.api.auth.web;

import com.playchale.api.auth.internal.service.AuthService;
import com.playchale.api.auth.web.dto.CodeResponse;
import com.playchale.api.auth.web.dto.RequestCodeRequest;
import com.playchale.api.shared.security.CurrentUser;
import com.playchale.api.users.api.UserSummary;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ways a signed-in player signs in: a phone number, an email address, or both on one account.
 * Adding one takes a code sent to it, like signing in.
 */
@RestController
class SignInMethodsController {

	private final AuthService auth;

	SignInMethodsController(AuthService auth) {
		this.auth = auth;
	}

	/** profiles.requestSignInCode: {"phone": ...} or {"email": ...}; the code goes there. */
	@PostMapping("/me/sign-in-methods/codes")
	CodeResponse requestCode(CurrentUser me, @Valid @RequestBody RequestCodeRequest request, HttpServletRequest http) {
		return new CodeResponse(auth.requestCodeToAdd(me.id(), request.phone(), request.email(), http.getRemoteAddr()).orElse(null));
	}

	record AddRequest(String phone, String email, String code) {
	}

	/** profiles.addSignInMethod: the phone or email with its code; it replaces the one of that kind they had. */
	@PostMapping("/me/sign-in-methods")
	UserSummary add(CurrentUser me, @RequestBody AddRequest request) {
		return auth.addSignInMethod(me.id(), request.phone(), request.email(), request.code());
	}

	/** profiles.removeSignInMethod: "phone" or "email", as long as the other stays. */
	@DeleteMapping("/me/sign-in-methods/{method}")
	UserSummary remove(CurrentUser me, @PathVariable String method) {
		return auth.removeSignInMethod(me.id(), method);
	}

}
