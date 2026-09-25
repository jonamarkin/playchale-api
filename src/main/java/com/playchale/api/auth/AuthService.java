package com.playchale.api.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.playchale.api.config.PlaychaleProperties;
import com.playchale.api.market.Market;
import com.playchale.api.users.User;
import com.playchale.api.users.UserRepository;
import com.playchale.api.web.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Signs players in with their phone number and a one-time code, and keeps them signed in with a
 * session. There are no passwords: entering the code proves they hold the phone. The first sign-in
 * for a number creates the account.
 */
@Service
public class AuthService {

	static final Duration CODE_LIFETIME = Duration.ofMinutes(10);

	static final int MAX_WRONG_GUESSES = 5;

	/** A number can be sent this many codes an hour, so nobody can flood a phone with texts. */
	static final int MAX_CODES_PER_HOUR = 5;

	static final Duration SESSION_LIFETIME = Duration.ofDays(30);

	/** Colours behind a new player's initials until they add a photo. Same palette as the web app. */
	private static final List<String> TINTS = List.of("#b7d3c9", "#a9c4f2", "#f2d4a9", "#d9b8e8", "#f5c9b3", "#c9a1d8", "#e8e8e4");

	private static final String BAD_NUMBER = "Enter a valid %s mobile number, e.g. 024 123 4567.";

	private static final SecureRandom random = new SecureRandom();

	/** What a successful sign-in hands back: the player, and a token for their session cookie. */
	public record SignedIn(User user, String token) {
	}

	private final SignInCodeRepository codes;

	private final UserSessionRepository sessions;

	private final UserRepository users;

	private final CodeSender sender;

	private final Clock clock;

	private final byte[] secret;

	private final String demoCode;

	AuthService(SignInCodeRepository codes, UserSessionRepository sessions, UserRepository users, CodeSender sender,
			Clock clock, PlaychaleProperties properties) {
		this.codes = codes;
		this.sessions = sessions;
		this.users = users;
		this.sender = sender;
		this.clock = clock;
		this.secret = properties.secret().getBytes(StandardCharsets.UTF_8);
		this.demoCode = properties.demoSignInCode();
	}

	/**
	 * Sends a sign-in code to a number. With a demo code configured (development only) it returns
	 * that code so the app can show it; otherwise it returns empty.
	 */
	public Optional<String> requestCode(String rawPhone) {
		var market = Market.get(Market.DEFAULT);
		var phone = market.normalisePhone(rawPhone)
			.orElseThrow(() -> AppException.invalid(BAD_NUMBER.formatted(market.countryName())));

		var now = clock.instant();
		if (codes.countByPhoneAndCreatedAtAfter(phone, now.minus(Duration.ofHours(1))) >= MAX_CODES_PER_HOUR) {
			throw AppException.conflict("Too many codes sent to this number. Try again in an hour.");
		}

		var code = demoCode.isEmpty() ? "%06d".formatted(random.nextInt(1_000_000)) : demoCode;
		codes.save(new SignInCode(phone, hashCode(phone, code), now, now.plus(CODE_LIFETIME)));
		sender.send(phone, code);
		return demoCode.isEmpty() ? Optional.empty() : Optional.of(demoCode);
	}

	/**
	 * Checks a code and, if it's right, signs the player in: creates their account on first sign-in
	 * and starts a session. Only the token's hash is stored, so a leaked database can't be used to
	 * sign in as anyone.
	 *
	 * <p>One transaction: either the code is used AND the session exists, or neither. By default an
	 * exception rolls the transaction back, which would undo the wrong-guess count and let someone
	 * guess forever, so AppException (a refusal, not a failure) commits instead. Nothing else is
	 * written before a refusal, so committing one never leaves half a sign-in behind.
	 */
	@Transactional(noRollbackFor = AppException.class)
	public SignedIn verify(String rawPhone, String code) {
		var market = Market.get(Market.DEFAULT);
		var phone = market.normalisePhone(rawPhone)
			.orElseThrow(() -> AppException.invalid(BAD_NUMBER.formatted(market.countryName())));

		var now = clock.instant();
		var live = codes.findLatestLiveForUpdate(phone, now)
			.orElseThrow(() -> AppException.invalid("That code has expired. Ask for a new one."));
		if (live.attempts() >= MAX_WRONG_GUESSES) {
			throw AppException.invalid("Too many wrong tries. Ask for a new code.");
		}
		if (!live.matches(hashCode(phone, code == null ? "" : code))) {
			// Changing a loaded entity is enough: Hibernate writes it when the transaction commits.
			live.recordWrongGuess();
			throw AppException.invalid("That code isn’t right. Check the SMS and try again.");
		}

		live.markUsed(now);
		var user = users.findByPhone(phone)
			.orElseGet(() -> users.save(new User(phone, market.country(), pickTint(), now)));

		var token = newToken();
		sessions.save(new UserSession(hashToken(token), user, now, now.plus(SESSION_LIFETIME)));
		return new SignedIn(user, token);
	}

	/** The player a session token belongs to. Not being signed in isn't an error, just no user. */
	@Transactional
	public Optional<User> currentUser(String token) {
		if (token == null || token.isEmpty()) {
			return Optional.empty();
		}
		var now = clock.instant();
		return sessions.findLive(hashToken(token), now).map(session -> {
			session.seen(now);
			return session.user();
		});
	}

	/** Ends a session. Signing out twice, or with no session, is fine. */
	@Transactional
	public void signOut(String token) {
		if (token != null && !token.isEmpty()) {
			sessions.deleteById(hashToken(token));
		}
	}

	/**
	 * Keys the hash with the server secret and the phone, so the same code for two numbers hashes
	 * differently, and the hashes are useless without the secret.
	 */
	private byte[] hashCode(String phone, String code) {
		try {
			var mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			return mac.doFinal((phone + ":" + code).getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256 is always available", e);
		}
	}

	/** A session token is 32 random bytes, far too many to guess, so a plain hash is enough. */
	static String hashToken(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("SHA-256 is always available", e);
		}
	}

	private static String newToken() {
		var bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String pickTint() {
		return TINTS.get(random.nextInt(TINTS.size()));
	}

}
