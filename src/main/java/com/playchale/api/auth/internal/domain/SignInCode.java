package com.playchale.api.auth.internal.domain;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

/**
 * A one-time sign-in code sent to a phone. Only a hash of the code is kept, so a leaked database
 * doesn't leak live codes. The rules for using one live here.
 */
@Entity
@Table(name = "sign_in_codes")
public class SignInCode {

	public static final Duration LIFETIME = Duration.ofMinutes(10);

	public static final int MAX_WRONG_GUESSES = 5;

	/** A number can be sent this many codes an hour, so nobody can flood a phone with texts. */
	public static final int MAX_PER_HOUR = 5;

	/** What a guess did. */
	public enum Attempt {

		/** Right, and the code is now used up. */
		ACCEPTED,
		/** Wrong, and counted against the code. */
		WRONG,
		/** Too many wrong guesses already; even the right code is refused now. */
		LOCKED

	}

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	private String phone;

	private byte[] codeHash;

	private int attempts;

	private Instant expiresAt;

	private Instant usedAt;

	private Instant createdAt;

	protected SignInCode() {
	}

	/** A new code for a number, good for {@link #LIFETIME}. */
	public SignInCode(String phone, byte[] codeHash, Instant now) {
		this.phone = phone;
		this.codeHash = codeHash.clone();
		this.createdAt = now;
		this.expiresAt = now.plus(LIFETIME);
	}

	/**
	 * Tries a guess, already hashed the same way as the code. A wrong guess counts against the code,
	 * and after {@link #MAX_WRONG_GUESSES} of them it's locked. A right one uses the code up.
	 */
	public Attempt attempt(byte[] guessHash, Instant now) {
		if (attempts >= MAX_WRONG_GUESSES) {
			return Attempt.LOCKED;
		}
		// MessageDigest.isEqual compares in constant time, so timing can't reveal how close a guess was.
		if (!MessageDigest.isEqual(codeHash, guessHash)) {
			attempts++;
			return Attempt.WRONG;
		}
		usedAt = now;
		return Attempt.ACCEPTED;
	}

	/** Not used and not expired. */
	public boolean isLive(Instant now) {
		return usedAt == null && expiresAt.isAfter(now);
	}

	public int getAttempts() {
		return attempts;
	}

}
