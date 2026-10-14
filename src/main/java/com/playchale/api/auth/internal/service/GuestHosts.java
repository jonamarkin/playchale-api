package com.playchale.api.auth.internal.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import com.playchale.api.auth.internal.domain.Session;
import com.playchale.api.auth.internal.domain.SessionToken;
import com.playchale.api.auth.internal.repository.SessionRepository;
import com.playchale.api.market.Market;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.shared.security.RateLimiter;
import com.playchale.api.users.api.GuestAccountMerged;
import com.playchale.api.users.api.GuestHostBecameMember;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.users.api.UserSummary;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hosting a game without an account: a guest host's account, the session on the device they host
 * from, and a manage key, a private link back to it from another browser. The key is shown once,
 * as the session's token is; only its hash is kept.
 *
 * <p>Making one says whether a number already has an account (it's refused, to sign in instead), so
 * a connection can only make a few: five an hour, twenty a day.
 */
@Service
public class GuestHosts {

	static final int PER_CONNECTION_PER_HOUR = 5;

	static final int PER_CONNECTION_PER_DAY = 20;

	private static final SecureRandom random = new SecureRandom();

	private final UserDirectory users;

	private final SessionRepository sessions;

	private final RateLimiter limiter;

	private final JdbcClient jdbc;

	private final Clock clock;

	GuestHosts(UserDirectory users, SessionRepository sessions, RateLimiter limiter, JdbcClient jdbc, Clock clock) {
		this.users = users;
		this.sessions = sessions;
		this.limiter = limiter;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** A guest host, signed in on this device: their account, the session's token, and their manage key. */
	public record Started(UserSummary user, String token, Duration validFor, String manageKey) {
	}

	/**
	 * Starts hosting without an account, under a name and a number for the place's country. On a
	 * device that already hosts as a guest, it's that guest still (with a new manage key).
	 */
	@Transactional
	public Started start(String name, String phone, String country, String termsVersion, String connection, String currentToken) {
		var current = guestSession(currentToken);
		if (current != null) {
			var user = users.find(current).orElseThrow(() -> BusinessException.unauthenticated("Sign in again."));
			return new Started(user, currentToken, Session.LIFETIME, newKey(current));
		}
		if (!limiter.tryAcquire("guest-host:" + connection, Duration.ofHours(1), PER_CONNECTION_PER_HOUR)
				|| !limiter.tryAcquire("guest-host-daily:" + connection, Duration.ofDays(1), PER_CONNECTION_PER_DAY)) {
			throw BusinessException.conflict("Too many tries from this connection. Try again later, or sign up.");
		}
		var market = Market.get(country);
		var e164 = market.normalisePhone(phone == null ? "" : phone)
			.orElseThrow(() -> BusinessException.invalid(phone == null || phone.isBlank() ? "Add your phone number, so your players can reach you."
					: "That number doesn’t look right. Check it and try again."));
		var user = users.registerGuestHost(name, e164, market.country(), termsVersion);
		var token = SessionToken.generate();
		sessions.save(new Session(token, user.id(), clock.instant(), true));
		return new Started(user, token.value(), Session.LIFETIME, newKey(user.id()));
	}

	/** The manage link, opened on another browser: a session there for the guest host it belongs to. */
	@Transactional
	public AuthService.SignedIn open(String key, String connection) {
		if (!limiter.tryAcquire("host-key:" + connection, Duration.ofHours(1), 20)) {
			throw BusinessException.conflict("Too many tries from this connection. Try again in an hour.");
		}
		var clean = key == null ? "" : key.strip();
		var userId = jdbc.sql("SELECT user_id FROM host_keys WHERE key_hash = :hash").param("hash", new SessionToken(clean).hash())
			.query(UUID.class).optional()
			.filter(users::isGuest)
			.orElseThrow(() -> BusinessException.notFound("That link doesn’t work any more. If you’ve signed up since, sign in instead."));
		jdbc.sql("UPDATE host_keys SET used_at = :now WHERE user_id = :user").param("now", clock.instant().atOffset(ZoneOffset.UTC))
			.param("user", userId).update();
		var token = SessionToken.generate();
		sessions.save(new Session(token, userId, clock.instant(), true));
		var user = users.find(userId).orElseThrow();
		return new AuthService.SignedIn(user, token.value(), Session.LIFETIME);
	}

	/** A new manage link, for a guest host who shared the old one too widely. The old one stops working. */
	@Transactional
	public String replaceKey(UUID guestId) {
		if (!users.isGuest(guestId)) {
			throw BusinessException.conflict("You have an account now: sign in with it instead.");
		}
		return newKey(guestId);
	}

	/** The guest host whose live session this is, or null. */
	UUID guestSession(String token) {
		if (token == null || token.isEmpty()) {
			return null;
		}
		return sessions.findByTokenHashAndExpiresAtAfter(new SessionToken(token).hash(), clock.instant())
			.filter(Session::isGuest).map(Session::getUserId).orElse(null);
	}

	/** They can sign in now: their sessions are an ordinary account's, and the manage link isn't needed. */
	@EventListener
	void on(GuestHostBecameMember e) {
		sessions.becameMember(e.userId());
		jdbc.sql("DELETE FROM host_keys WHERE user_id = :user").param("user", e.userId()).update();
	}

	/** Their games went to the account they had: the guest account's ways in go. */
	@EventListener
	void on(GuestAccountMerged e) {
		sessions.deleteAllOf(e.guestId());
		jdbc.sql("DELETE FROM host_keys WHERE user_id = :user").param("user", e.guestId()).update();
	}

	private String newKey(UUID userId) {
		var bytes = new byte[24];
		random.nextBytes(bytes);
		var key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		jdbc.sql("""
				INSERT INTO host_keys (user_id, key_hash, created_at) VALUES (:user, :hash, :now)
				ON CONFLICT (user_id) DO UPDATE SET key_hash = EXCLUDED.key_hash, created_at = EXCLUDED.created_at, used_at = NULL
				""").param("user", userId).param("hash", new SessionToken(key).hash()).param("now", clock.instant().atOffset(ZoneOffset.UTC)).update();
		return key;
	}

}
