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

	/** A phone or address can be sent this many codes an hour, so nobody can flood it. */
	public static final int MAX_PER_HOUR = 5;

	/**
	 * And this many a day. Five wrong guesses a code and five codes an hour would let someone keep
	 * guessing at one person's six-digit codes all month (about 18,000 guesses, a 2% chance); a day's
	 * limit on codes and on wrong guesses keeps that under 0.6% a year.
	 */
	public static final int MAX_PER_DAY = 10;

	/** Wrong guesses at one phone's or address's codes in a day, across all of them, before it waits a day. */
	public static final int MAX_WRONG_PER_DAY = 15;

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

	/** "sms" or "email". */
	private String channel;

	/** The phone (E.164) or email (lower-case) it was sent to. */
	private String recipient;

	private byte[] codeHash;

	private int attempts;

	private Instant expiresAt;

	private Instant usedAt;

	private Instant createdAt;

	protected SignInCode() {
	}

	/** A new code for a phone or an email address, good for {@link #LIFETIME}. */
	public SignInCode(String channel, String recipient, byte[] codeHash, Instant now) {
		this.channel = channel;
		this.recipient = recipient;
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
