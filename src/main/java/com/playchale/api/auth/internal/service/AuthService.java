package com.playchale.api.auth.internal.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.playchale.api.auth.internal.domain.Session;
import com.playchale.api.auth.internal.domain.SessionToken;
import com.playchale.api.auth.internal.domain.SignInCode;
import com.playchale.api.auth.internal.repository.SessionRepository;
import com.playchale.api.auth.internal.repository.SignInCodeRepository;
import com.playchale.api.integration.email.EmailSender;
import com.playchale.api.integration.sms.SmsSender;
import com.playchale.api.market.Market;
import com.playchale.api.shared.config.PlaychaleProperties;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.users.api.UserSummary;
import com.playchale.api.shared.security.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Signs players in with their phone number and a one-time code, and keeps them signed in with a
 * session. There are no passwords: entering the code proves they hold the phone. The first sign-in
 * for a number registers the player.
 */
@Service
@EnableConfigurationProperties(SignInLimits.class)
public class AuthService {

	private static final Logger log = LoggerFactory.getLogger(AuthService.class);

	private static final SecureRandom random = new SecureRandom();

	/** A successful sign-in: the player, and the token for their session cookie with how long it lasts. */
	public record SignedIn(UserSummary user, String token, Duration validFor) {
	}

	/** Which ways of signing in this copy of the API can offer: each needs its provider configured. */
	public record Options(boolean phone, boolean email) {
	}

	/** Where a code goes: "sms" to a phone (E.164) or "email" to an address (lower-case). */
	private record Recipient(String channel, String address) {

		boolean bySms() {
			return "sms".equals(channel);
		}

	}

	/** Deliberately loose: something@something.something. The code arriving is the real test. */
	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

	private final SignInCodeRepository codes;

	private final SessionRepository sessions;

	private final UserDirectory users;

	private final ObjectProvider<SmsSender> sms;

	private final ObjectProvider<EmailSender> email;

	private final RateLimiter limiter;

	private final SignInLimits limits;

	private final Clock clock;

	private final SecretKeySpec codeKey;

	private final String demoCode;

	AuthService(SignInCodeRepository codes, SessionRepository sessions, UserDirectory users, ObjectProvider<SmsSender> sms,
			ObjectProvider<EmailSender> email, RateLimiter limiter, SignInLimits limits, Clock clock, PlaychaleProperties properties) {
		this.codes = codes;
		this.sessions = sessions;
		this.users = users;
		this.sms = sms;
		this.email = email;
		this.limiter = limiter;
		this.limits = limits;
		this.clock = clock;
		this.codeKey = new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		this.demoCode = properties.demoSignInCode();
	}

	/** auth.options: which ways of signing in to offer. */
	public Options options() {
		return new Options(sms.getIfAvailable() != null, email.getIfAvailable() != null);
	}

	/**
	 * Sends a sign-in code to a phone (by SMS) or an email address: give one or the other. Returns the
	 * code itself only when a demo code is configured (the dev profile), so the web app can show it.
	 *
	 * @param connection the client's address, for the per-connection limit
	 */
	@Transactional
	public Optional<String> requestCode(String typedPhone, String typedEmail, String connection) {
		var to = recipient(typedPhone, typedEmail);
		var now = clock.instant();
		if (codes.countByRecipientAndCreatedAtAfter(to.address(), now.minus(Duration.ofHours(1))) >= SignInCode.MAX_PER_HOUR) {
			throw BusinessException.conflict(to.bySms() ? "Too many codes sent to this number. Try again in an hour."
					: "Too many codes sent to this address. Try again in an hour.");
		}
		if (!limiter.tryAcquire("sign-in:connection:" + connection, Duration.ofHours(1), limits.perConnectionPerHour())) {
			throw BusinessException.conflict("Too many codes asked for from this connection. Try again in an hour.");
		}
		// Counted per channel: texts cost money, emails next to nothing.
		if (!limiter.tryAcquire("sign-in:" + to.channel(), Duration.ofDays(1), limits.perDay())) {
			log.error("Sign-in codes by {} stopped: the daily limit of {} is used up. Check for abuse before raising it.", to.channel(),
					limits.perDay());
			throw BusinessException.conflict("We can’t send sign-in codes right now. Please try again later.");
		}

		var code = demoCode.isEmpty() ? "%06d".formatted(random.nextInt(1_000_000)) : demoCode;
		codes.save(new SignInCode(to.channel(), to.address(), hash(to.address(), code), now));
		var message = "Your PlayChale code is %s. It expires in 10 minutes. Don’t share it.".formatted(code);
		if (to.bySms()) {
			sms.getObject().send(to.address(), message);
		}
		else {
			email.getObject().send(to.address(), "Your PlayChale sign-in code: %s".formatted(code),
					message + "\n\nIf you didn’t try to sign in to PlayChale, you can ignore this email.");
		}
		return demoCode.isEmpty() ? Optional.empty() : Optional.of(demoCode);
	}

	/**
	 * Checks a code sent to a phone or an email address and, if it's right, signs the player in:
	 * registers them on their first sign-in and starts a session. A phone and an email are separate
	 * accounts: signing in with one never reaches the other's.
	 *
	 * <p>One transaction: either the code is used AND the session exists, or neither. By default an
	 * exception rolls the transaction back, which would undo the wrong-guess count and let someone
	 * guess forever, so a BusinessException (a refusal, not a failure) commits instead. Only the
	 * guess count is written before a refusal, so committing one never leaves half a sign-in behind.
	 */
	@Transactional(noRollbackFor = BusinessException.class)
	public SignedIn signIn(String typedPhone, String typedEmail, String code) {
		var from = recipient(typedPhone, typedEmail);
		var now = clock.instant();

		var live = codes.lockLatestLive(from.address(), now)
			.orElseThrow(() -> BusinessException.invalid("That code has expired. Ask for a new one."));
		// A loaded entity is saved when the transaction commits, so the guess count sticks.
		switch (live.attempt(hash(from.address(), code), now)) {
			case LOCKED -> throw BusinessException.invalid("Too many wrong tries. Ask for a new code.");
			case WRONG -> throw BusinessException.invalid(from.bySms() ? "That code isn’t right. Check the SMS and try again."
					: "That code isn’t right. Check the email and try again.");
			case ACCEPTED -> {
				// Signed in: carry on.
			}
		}

		// The code row stays locked until commit, so two sign-ins at once can't both register them.
		var country = Market.get(Market.DEFAULT).country();
		var user = from.bySms() ? users.registerOrFind(from.address(), country) : users.registerOrFindByEmail(from.address(), country);
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

	/**
	 * Where a code goes, as it's stored: a phone in E.164 or an email in lower case. Refused with a
	 * message saying what's wrong, including when that way of signing in isn't set up here.
	 */
	private Recipient recipient(String typedPhone, String typedEmail) {
		var hasPhone = typedPhone != null && !typedPhone.isBlank();
		var hasEmail = typedEmail != null && !typedEmail.isBlank();
		if (hasPhone == hasEmail) {
			throw BusinessException.invalid("Enter your mobile number or your email address.");
		}
		if (hasPhone) {
			if (sms.getIfAvailable() == null) {
				throw BusinessException.conflict("Signing in with a phone number isn’t available yet. Use your email address.");
			}
			var market = Market.get(Market.DEFAULT);
			return new Recipient("sms", market.normalisePhone(typedPhone)
				.orElseThrow(() -> BusinessException.invalid("Enter a valid %s mobile number, e.g. 024 123 4567.".formatted(market.countryName()))));
		}
		if (email.getIfAvailable() == null) {
			throw BusinessException.conflict("Signing in with email isn’t available yet. Use your mobile number.");
		}
		var address = typedEmail.strip().toLowerCase(Locale.ROOT);
		if (address.length() > 254 || !EMAIL.matcher(address).matches()) {
			throw BusinessException.invalid("Enter an email address like name@example.com.");
		}
		return new Recipient("email", address);
	}

	/**
	 * HMAC-SHA256 keyed by the server secret, over the recipient and the code: the same code for two
	 * people hashes differently, and the hashes are useless without the secret.
	 */
	private byte[] hash(String recipient, String code) {
		try {
			var mac = Mac.getInstance("HmacSHA256");
			mac.init(codeKey);
			return mac.doFinal((recipient + ":" + code).getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256 is always available", e);
		}
	}

}
