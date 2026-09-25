package com.playchale.api.auth;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

/** A one-time sign-in code. Only a hash is stored, so a leaked database doesn't leak live codes. */
@Entity
@Table(name = "sign_in_codes")
class SignInCode {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	private String phone;

	private byte[] codeHash;

	/** Wrong guesses so far; the code stops working after a few. */
	private int attempts;

	private Instant expiresAt;

	private Instant usedAt;

	private Instant createdAt;

	protected SignInCode() {
	}

	SignInCode(String phone, byte[] codeHash, Instant now, Instant expiresAt) {
		this.phone = phone;
		this.codeHash = codeHash;
		this.createdAt = now;
		this.expiresAt = expiresAt;
	}

	/** MessageDigest.isEqual compares in constant time, so timing can't reveal how close a guess was. */
	boolean matches(byte[] hash) {
		return MessageDigest.isEqual(codeHash, hash);
	}

	int attempts() {
		return attempts;
	}

	void recordWrongGuess() {
		attempts++;
	}

	void markUsed(Instant now) {
		usedAt = now;
	}

}
