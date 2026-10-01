package com.playchale.api.notifications.internal.service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.notifications.internal.domain.EmailPreference;
import com.playchale.api.notifications.internal.repository.EmailPreferenceRepository;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.users.api.UserSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What PlayChale may send someone by email, and the settings behind it.
 *
 * <p>Service email — your game, a league you entered, your sign-in code — needs no agreement, but a
 * whole category can be kept out of an inbox. Marketing email is off until someone turns it on. The
 * difference is enforced here rather than at each call site, so a new kind of email cannot quietly
 * be sent to people who never asked for any.
 */
@Service
public class EmailService {

	private final EmailPreferenceRepository preferences;

	private final UserDirectory users;

	private final Clock clock;

	EmailService(EmailPreferenceRepository preferences, UserDirectory users, Clock clock) {
		this.preferences = preferences;
		this.users = users;
		this.clock = clock;
	}

	/**
	 * @param muted      the categories kept out of their inbox
	 * @param marketing  whether they agreed to email about PlayChale itself
	 * @param digest     "weekly" or "off"
	 * @param address    where it would go, or null if we have no address for them
	 * @param categories every category there is, so the web app doesn't hard-code the list
	 */
	public record Settings(List<String> muted, boolean marketing, String digest, String address, List<String> categories) {
	}

	/**
	 * Where to email someone. The address they signed in with is preferred because a code has been
	 * delivered to it; the one on their profile is whatever they typed for receipts, so it is only a
	 * fallback. Null when we have neither, or once the account is deleted.
	 */
	static String addressOf(UserSummary user) {
		if (user == null) {
			return null;
		}
		var proven = user.signInEmail();
		return proven != null && !proven.isBlank() ? proven : blankToNull(user.email());
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	@Transactional(readOnly = true)
	public Settings settings(UUID me) {
		var preference = preferences.findById(me);
		var address = addressOf(users.find(me).orElse(null));
		return new Settings(preference.map(EmailPreference::getMuted).orElse(List.of()), preference.map(EmailPreference::isMarketing).orElse(false),
				preference.map(EmailPreference::getDigest).orElse("weekly"), address, EmailCategories.ALL);
	}

	/** Keeps whole categories out of their inbox. An unknown category is a mistake worth saying out loud. */
	@Transactional
	public Settings mute(UUID me, List<String> categories) {
		for (var category : categories) {
			if (!EmailCategories.known(category)) {
				throw BusinessException.invalid("There’s no email category called “%s”.".formatted(category));
			}
		}
		row(me).mute(categories);
		return settings(me);
	}

	/** Their answer to "may we email you about PlayChale itself?", with the date it was given. */
	@Transactional
	public Settings setMarketing(UUID me, boolean wanted) {
		row(me).setMarketing(wanted, clock.instant());
		return settings(me);
	}

	@Transactional
	public Settings setDigest(UUID me, String choice) {
		if (!List.of("weekly", "off").contains(choice)) {
			throw BusinessException.invalid("The digest is either weekly or off.");
		}
		row(me).setDigest(choice, clock.instant());
		return settings(me);
	}

	/**
	 * What one tap in an email does, with nobody signed in. Everything consent-gated goes off; service
	 * email about something they are in stays, because stopping that would hide a cancelled game from
	 * them. The page says exactly this.
	 */
	@Transactional
	public Optional<String> unsubscribe(UUID token) {
		return preferences.findByToken(token).map(preference -> {
			preference.unsubscribeAll(clock.instant());
			var user = users.find(preference.getUserId()).orElse(null);
			return user == null ? "" : user.name();
		});
	}

	/** Whether a service email of this kind may go out, and where to. Null when it may not. */
	@Transactional(readOnly = true)
	public String addressFor(UUID userId, String notificationKind) {
		var category = EmailCategories.of(notificationKind);
		if (category != null && preferences.findById(userId).map(p -> p.getMuted().contains(category)).orElse(false)) {
			return null;
		}
		return addressOf(users.find(userId).orElse(null));
	}

	/** Everyone who agreed to email about PlayChale itself. */
	@Transactional(readOnly = true)
	public List<UUID> marketingAudience() {
		return preferences.marketingUserIds();
	}

	/** Everyone who agreed to the weekly digest. Agreeing to marketing is the gate; the digest is the choice within it. */
	@Transactional(readOnly = true)
	public List<UUID> digestAudience() {
		return preferences.digestUserIds();
	}

	/** The link that stops it, for the footer of every email that needed agreement. */
	@Transactional(readOnly = true)
	public Optional<UUID> tokenOf(UUID userId) {
		return preferences.findById(userId).map(EmailPreference::getToken);
	}

	private EmailPreference row(UUID me) {
		return preferences.findById(me).orElseGet(() -> preferences.save(new EmailPreference(me, clock.instant())));
	}

}
