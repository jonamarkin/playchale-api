package com.playchale.api.auth.internal.service;

import java.text.ParseException;
import java.util.Locale;
import java.util.Set;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.playchale.api.shared.error.BusinessException;
import org.springframework.stereotype.Component;

/**
 * Checks the ID token Google gives the web app when someone signs in with Google: signed by Google
 * (RS256, Google's published keys), issued by Google, meant for our client ID, not expired, and
 * with an email address Google has verified.
 */
@Component
class GoogleIdTokens {

	/** A Google account: its stable ID and its verified email, lower-case. */
	record Identity(String sub, String email) {
	}

	private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

	private final DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();

	GoogleIdTokens(JWKSource<SecurityContext> googleSigningKeys, GoogleSignInProperties properties) {
		processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, googleSigningKeys));
		processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(properties.clientId(), new JWTClaimsSet.Builder().build(),
				Set.of("sub", "email", "iss", "exp")));
	}

	Identity verify(String credential) {
		JWTClaimsSet claims;
		try {
			claims = processor.process(credential == null ? "" : credential, null);
		}
		catch (ParseException | BadJOSEException | JOSEException e) {
			throw BusinessException.invalid("Signing in with Google didn’t work. Try again.");
		}
		try {
			if (!ISSUERS.contains(claims.getIssuer())) {
				throw BusinessException.invalid("Signing in with Google didn’t work. Try again.");
			}
			if (!Boolean.TRUE.equals(claims.getBooleanClaim("email_verified"))) {
				throw BusinessException.invalid("Your Google account’s email address isn’t verified yet. Verify it with Google, or sign in with a code.");
			}
			return new Identity(claims.getSubject(), claims.getStringClaim("email").strip().toLowerCase(Locale.ROOT));
		}
		catch (ParseException e) {
			throw BusinessException.invalid("Signing in with Google didn’t work. Try again.");
		}
	}

}
