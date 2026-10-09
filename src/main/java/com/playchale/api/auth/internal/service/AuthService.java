package com.playchale.api.auth.internal.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.playchale.api.auth.api.ContactVerified;
import com.playchale.api.auth.internal.domain.Session;
import com.playchale.api.auth.internal.domain.SessionToken;
import com.playchale.api.auth.internal.domain.SignInCode;
import com.playchale.api.auth.internal.repository.SessionRepository;
import com.playchale.api.auth.internal.repository.SignInCodeRepository;
import com.playchale.api.integration.email.Email;
import com.playchale.api.integration.email.EmailLayout;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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

	/**
	 * Which ways of signing in this copy of the API can offer: each needs its provider configured.
	 * {@code googleClientId} is set when Google sign-in is (the web app needs it to show the button).
	 */
	public record Options(boolean phone, boolean email, String googleClientId) {
	}

	/**
	 * Where a code goes: "sms" to a phone (E.164) or "email" to an address (lower-case). A phone's
	 * {@code country} is the one its number belongs to; null for an email.
	 */
	private record Recipient(String channel, String address, String country) {

		Recipient(String channel, String address) {
			this(channel, address, null);
		}

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

	/** The web app's address, for the logo and links in emails. */
	private final String webApp;

	private final GoogleSignInProperties google;

	private final GoogleIdTokens googleTokens;

	private final TransactionTemplate tx;

	private final ApplicationEventPublisher events;

	AuthService(SignInCodeRepository codes, SessionRepository sessions, UserDirectory users, ObjectProvider<SmsSender> sms,
			ObjectProvider<EmailSender> email, RateLimiter limiter, SignInLimits limits, Clock clock, PlaychaleProperties properties,
			GoogleSignInProperties google, GoogleIdTokens googleTokens, TransactionTemplate tx, ApplicationEventPublisher events) {
		this.tx = tx;
		this.events = events;
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
		this.webApp = properties.webApp();
		this.google = google;
		this.googleTokens = googleTokens;
	}

	/** auth.options: which ways of signing in to offer. */
	public Options options() {
		return new Options(sms.getIfAvailable() != null, email.getIfAvailable() != null, google.enabled() ? google.clientId() : null);
	}

	/**
	 * Sends a sign-in code to a phone (by SMS) or an email address: give one or the other. Returns the
	 * code itself only when a demo code is configured (the dev profile), so the web app can show it.
	 *
	 * @param connection the client's address, for the per-connection limit
	 */
	public Optional<String> requestCode(String typedPhone, String typedEmail, String connection) {
		return send(recipient(typedPhone, typedEmail), connection, false);
	}

	/**
	 * Sends a code to a phone or an email address a signed-in player wants to sign in with too. Refused
	 * when it's already theirs, or someone else's: two accounts can't share a way in.
	 */
	public Optional<String> requestCodeToAdd(UUID userId, String typedPhone, String typedEmail, String connection) {
		var to = recipient(typedPhone, typedEmail);
		requireFree(userId, to);
		return send(to, connection, true);
	}

	/**
	 * Checks the code sent to a phone or email and makes it a way for the player to sign in, in place
	 * of the one of that kind they had (so it also changes a number). Wrong guesses count as they do
	 * when signing in.
	 */
	@Transactional(noRollbackFor = BusinessException.class)
	public UserSummary addSignInMethod(UUID userId, String typedPhone, String typedEmail, String code) {
		var from = recipient(typedPhone, typedEmail);
		check(from, code, clock.instant());
		requireFree(userId, from);
		var user = users.addSignInMethod(userId, from.bySms() ? "phone" : "email", from.address());
		verified(userId, from);
		return user;
	}

	/** Stops the player signing in with their phone ("phone") or email ("email"), keeping the other. */
	@Transactional
	public UserSummary removeSignInMethod(UUID userId, String method) {
		if (!"phone".equals(method) && !"email".equals(method)) {
			throw BusinessException.notFound("No such way of signing in.");
		}
		return users.removeSignInMethod(userId, method);
	}

	private void requireFree(UUID userId, Recipient to) {
		users.signsInWith(to.bySms() ? "phone" : "email", to.address()).ifPresent(owner -> {
			if (owner.equals(userId)) {
				throw BusinessException.invalid(to.bySms() ? "That number is already on your account." : "That address is already on your account.");
			}
			throw BusinessException.conflict((to.bySms() ? "That number is on another PlayChale account." : "That address is on another PlayChale account.")
					+ " To bring the two together, write to support@playchale.com.");
		});
	}

	/**
	 * A new code to a phone or email, within the limits; {@code adding} words it for adding it to an account.
	 *
	 * <p>The code is saved in a short transaction of its own and sent after it has committed. Sending
	 * is a call to Resend or the SMS provider that can take half a second; holding a database
	 * connection through it would let a rush of sign-ins (a launch, a link going round) use up every
	 * connection and stall the whole app. If sending fails, the code is taken back out, so it doesn't
	 * count against the person's limits.
	 */
	private Optional<String> send(Recipient to, String connection, boolean adding) {
		var issued = tx.execute(status -> issue(to, connection));
		var message = "Your PlayChale code is %s. It expires in 10 minutes. Don’t share it.".formatted(issued.code());
		try {
			if (to.bySms()) {
				// Plain ASCII: one character outside the SMS alphabet (a curly apostrophe, say) makes the
				// whole text Unicode, where a part holds 70 characters, not 160, and this code would cost two.
				sms.getObject().send(to.address(), "Your PlayChale code is %s. It expires in 10 minutes. Don't share it.".formatted(issued.code()));
			}
			else {
				email.getObject().send(signInEmail(to.address(), issued.code(), message, adding));
			}
		}
		catch (RuntimeException failed) {
			tx.executeWithoutResult(status -> codes.deleteById(issued.id()));
			throw failed;
		}
		return demoCode.isEmpty() ? Optional.empty() : Optional.of(demoCode);
	}

	/** A code saved and ready to send. */
	private record Issued(UUID id, String code) {
	}

	/** Checks the limits and saves a new code, in the caller's (short) transaction. */
	private Issued issue(Recipient to, String connection) {
		var now = clock.instant();
		if (codes.countByRecipientAndCreatedAtAfter(to.address(), now.minus(Duration.ofHours(1))) >= SignInCode.MAX_PER_HOUR) {
			throw BusinessException.conflict(to.bySms() ? "Too many codes sent to this number. Try again in an hour."
					: "Too many codes sent to this address. Try again in an hour.");
		}
		if (codes.countByRecipientAndCreatedAtAfter(to.address(), now.minus(Duration.ofDays(1))) >= SignInCode.MAX_PER_DAY) {
			throw BusinessException.conflict(to.bySms() ? "Too many codes sent to this number today. Try again tomorrow."
					: "Too many codes sent to this address today. Try again tomorrow.");
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
		var saved = codes.save(new SignInCode(to.channel(), to.address(), hash(to.address(), code), now));
		return new Issued(saved.getId(), code);
	}

	/** The sign-in code email, in PlayChale's look, with a plain-text copy. */
	private Email signInEmail(String address, String code, String message, boolean adding) {
		var subject = "Your PlayChale sign-in code: %s".formatted(code);
		var why = adding ? "You got this email because someone asked to add this address to their PlayChale account."
				: "You got this email because someone asked to sign in to PlayChale with this address.";
		var html = EmailLayout.render(webApp, "sign-in-code", subject, message, why, Map.of("code", code));
		var text = message + "\n\nIf you didn’t try to sign in to PlayChale, you can ignore this email. Nobody can sign in without the code.\n\n"
				+ why;
		return new Email(address, subject, text, html);
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
		return signIn(typedPhone, typedEmail, code, null);
	}

	/**
	 * As above. {@code country} is where someone new is, as the web app guesses it (they can change it
	 * in onboarding): used for a new account by email. A phone number carries its own country.
	 */
	@Transactional(noRollbackFor = BusinessException.class)
	public SignedIn signIn(String typedPhone, String typedEmail, String code, String country) {
		var from = recipient(typedPhone, typedEmail);
		var now = clock.instant();
		check(from, code, now);

		// The code row stays locked until commit, so two sign-ins at once can't both register them.
		var user = from.bySms() ? users.registerOrFind(from.address(), from.country())
				: users.registerOrFindByEmail(from.address(), Market.get(country).country());
		var token = SessionToken.generate();
		sessions.save(new Session(token, user.id(), now));
		verified(user.id(), from);
		return new SignedIn(user, token.value(), Session.LIFETIME);
	}

	/** They hold this number or address: anything held under it for them can be theirs now. */
	private void verified(UUID userId, Recipient from) {
		events.publishEvent(from.bySms() ? new ContactVerified(userId, from.address(), null) : new ContactVerified(userId, null, from.address()));
	}

	/**
	 * The code sent to a phone or email, checked. A loaded entity is saved when the transaction commits,
	 * so a wrong guess counts even though the caller is refused (see noRollbackFor on the callers).
	 */
	private void check(Recipient from, String code, Instant now) {
		var live = codes.lockLatestLive(from.address(), now)
			.orElseThrow(() -> BusinessException.invalid("That code has expired. Ask for a new one."));
		// Across every code this day, not just this one: asking for new codes doesn't buy more guesses.
		if (codes.wrongGuessesSince(from.address(), now.minus(Duration.ofDays(1))) >= SignInCode.MAX_WRONG_PER_DAY) {
			throw BusinessException.invalid("Too many wrong tries today. Try again tomorrow, or sign in another way.");
		}
		switch (live.attempt(hash(from.address(), code), now)) {
			case LOCKED -> throw BusinessException.invalid("Too many wrong tries. Ask for a new code.");
			case WRONG -> throw BusinessException.invalid(from.bySms() ? "That code isn’t right. Check the SMS and try again."
					: "That code isn’t right. Check the email and try again.");
			case ACCEPTED -> {
				// The code was right: carry on.
			}
		}
	}

	/**
	 * Signs someone in with the ID token Google gave the web app. Their Google account finds (or
	 * makes) their PlayChale account: see {@link UserDirectory#registerOrFindByGoogle}. {@code country}
	 * is the web app's guess, for a new account.
	 */
	@Transactional
	public SignedIn signInWithGoogle(String credential, String country) {
		if (!google.enabled()) {
			throw BusinessException.conflict("Signing in with Google isn’t available yet. Use a code instead.");
		}
		var who = googleTokens.verify(credential);
		var user = users.registerOrFindByGoogle(who.sub(), who.email(), Market.get(country).country());
		var token = SessionToken.generate();
		sessions.save(new Session(token, user.id(), clock.instant()));
		// Google only hands over an email it has verified (GoogleIdTokens checks).
		if (who.email() != null) {
			events.publishEvent(new ContactVerified(user.id(), null, who.email().toLowerCase(Locale.ROOT)));
		}
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
			var phone = Market.get(Market.DEFAULT).normaliseAnyPhone(typedPhone)
				.orElseThrow(() -> BusinessException.invalid("Enter a valid mobile number, like 024 123 4567, or with its country code (+44 7400 123456)."));
			return new Recipient("sms", phone.e164(), phone.country());
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
