package com.playchale.api.auth.internal.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.playchale.api.auth.internal.domain.Session;
import com.playchale.api.auth.internal.domain.SessionToken;
import com.playchale.api.auth.internal.domain.SignInCode;
import com.playchale.api.auth.internal.repository.SessionRepository;
import com.playchale.api.auth.internal.repository.SignInCodeRepository;
import com.playchale.api.integration.sms.SmsSender;
import com.playchale.api.market.Market;
import com.playchale.api.shared.config.PlaychaleProperties;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.users.api.UserSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Signs players in with their phone number and a one-time code, and keeps them signed in with a
 * session. There are no passwords: entering the code proves they hold the phone. The first sign-in
 * for a number registers the player.
 */
@Service
public class AuthService {

	private static final SecureRandom random = new SecureRandom();

	/** A successful sign-in: the player, and the token for their session cookie with how long it lasts. */
	public record SignedIn(UserSummary user, String token, Duration validFor) {
	}

	private final SignInCodeRepository codes;

	private final SessionRepository sessions;

	private final UserDirectory users;

	private final SmsSender sms;

	private final Clock clock;

	private final SecretKeySpec codeKey;

	private final String demoCode;

	AuthService(SignInCodeRepository codes, SessionRepository sessions, UserDirectory users, SmsSender sms, Clock clock,
			PlaychaleProperties properties) {
		this.codes = codes;
		this.sessions = sessions;
		this.users = users;
		this.sms = sms;
		this.clock = clock;
		this.codeKey = new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		this.demoCode = properties.demoSignInCode();
	}

	/**
	 * Texts a sign-in code to a number. Returns the code itself only when a demo code is configured
	 * (the dev profile), so the web app can show it; otherwise empty.
	 */
	@Transactional
	public Optional<String> requestCode(String typedPhone) {
		var phone = e164(typedPhone);
		var now = clock.instant();
		if (codes.countByPhoneAndCreatedAtAfter(phone, now.minus(Duration.ofHours(1))) >= SignInCode.MAX_PER_HOUR) {
			throw BusinessException.conflict("Too many codes sent to this number. Try again in an hour.");
		}

		var code = demoCode.isEmpty() ? "%06d".formatted(random.nextInt(1_000_000)) : demoCode;
		codes.save(new SignInCode(phone, hash(phone, code), now));
		sms.send(phone, "Your PlayChale code is %s. It expires in 10 minutes. Don’t share it.".formatted(code));
		return demoCode.isEmpty() ? Optional.empty() : Optional.of(demoCode);
	}

	/**
	 * Checks a code and, if it's right, signs the player in: registers them on their first sign-in
	 * and starts a session.
	 *
	 * <p>One transaction: either the code is used AND the session exists, or neither. By default an
	 * exception rolls the transaction back, which would undo the wrong-guess count and let someone
	 * guess forever, so a BusinessException (a refusal, not a failure) commits instead. Only the
	 * guess count is written before a refusal, so committing one never leaves half a sign-in behind.
	 */
	@Transactional(noRollbackFor = BusinessException.class)
	public SignedIn signIn(String typedPhone, String code) {
		var phone = e164(typedPhone);
		var now = clock.instant();

		var live = codes.lockLatestLive(phone, now)
			.orElseThrow(() -> BusinessException.invalid("That code has expired. Ask for a new one."));
		// A loaded entity is saved when the transaction commits, so the guess count sticks.
		switch (live.attempt(hash(phone, code), now)) {
			case LOCKED -> throw BusinessException.invalid("Too many wrong tries. Ask for a new code.");
			case WRONG -> throw BusinessException.invalid("That code isn’t right. Check the SMS and try again.");
			case ACCEPTED -> {
				// Signed in: carry on.
			}
		}

		// The code row stays locked until commit, so two sign-ins for one number can't both register them.
		var user = users.registerOrFind(phone, Market.get(Market.DEFAULT).country());
		var token = SessionToken.generate();
		sessions.save(new Session(token, user.id(), now));
		return new SignedIn(user, token.value(), Session.LIFETIME);
	}

	/** The player a session token belongs to, while the session is live. Runs on every signed-in request. */
	@Transactional
	public Optional<UUID> userIdFor(String token) {
		if (token == null || token.isEmpty()) {
			return Optional.empty();
		}
		var now = clock.instant();
		return sessions.findByTokenHashAndExpiresAtAfter(new SessionToken(token).hash(), now).map(session -> {
			session.seen(now);
			return session.getUserId();
		});
	}

	@Transactional(readOnly = true)
	public Optional<UserSummary> user(UUID id) {
		return users.find(id);
	}

	/** Ends a session. Signing out twice, or with no session, is fine. */
	@Transactional
	public void signOut(String token) {
		if (token != null && !token.isEmpty()) {
			sessions.deleteById(new SessionToken(token).hash());
		}
	}

	/** The number as it's stored (E.164), or a refusal saying what a valid one looks like. */
	private static String e164(String typed) {
		var market = Market.get(Market.DEFAULT);
		return market.normalisePhone(typed)
			.orElseThrow(() -> BusinessException.invalid("Enter a valid %s mobile number, e.g. 024 123 4567.".formatted(market.countryName())));
	}

	/**
	 * HMAC-SHA256 keyed by the server secret, over the phone and the code: the same code for two
	 * numbers hashes differently, and the hashes are useless without the secret.
	 */
	private byte[] hash(String phone, String code) {
		try {
			var mac = Mac.getInstance("HmacSHA256");
			mac.init(codeKey);
			return mac.doFinal((phone + ":" + code).getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256 is always available", e);
		}
	}

}
