package com.playchale.api.auth.internal.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The secret a signed-in browser holds in its cookie. Only its hash is ever stored, so a leaked
 * database can't be used to sign in as anyone.
 */
public record SessionToken(String value) {

	private static final SecureRandom random = new SecureRandom();

	/** 32 random bytes: far too many to guess, which is why a plain (unkeyed) hash is enough. */
	public static SessionToken generate() {
		var bytes = new byte[32];
		random.nextBytes(bytes);
		return new SessionToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
	}

	/** SHA-256 of the token, as hex: what the sessions table stores and looks up by. */
	public String hash() {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("SHA-256 is always available", e);
		}
	}

	/** Never print the token itself, e.g. in logs. */
	@Override
	public String toString() {
		return "SessionToken[hidden]";
	}

}
