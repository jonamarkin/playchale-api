package com.playchale.api.notifications.internal.service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import com.playchale.api.integration.push.AppPushSender;
import com.playchale.api.integration.push.PushSender;
import com.playchale.api.integration.push.PushTarget;
import com.playchale.api.notifications.internal.domain.AppPushToken;
import com.playchale.api.notifications.internal.domain.PushPreference;
import com.playchale.api.notifications.internal.domain.PushSubscription;
import com.playchale.api.notifications.internal.repository.AppPushTokenRepository;
import com.playchale.api.notifications.internal.repository.PushPreferenceRepository;
import com.playchale.api.notifications.internal.repository.PushSubscriptionRepository;
import com.playchale.api.shared.error.BusinessException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Phone notifications: the browsers that allow them, what someone keeps off their phone, and
 * sending each notification on to them once it's saved. Only when push is set up on the server
 * (a {@link PushSender} exists); otherwise the web app doesn't offer them.
 */
@Service
public class PushService {

	private final PushSubscriptionRepository subscriptions;

	private final PushPreferenceRepository preferences;

	private final ObjectProvider<PushSender> sender;

	private final AppPushTokenRepository appTokens;

	private final ObjectProvider<AppPushSender> appSender;

	private final ObjectMapper json;

	/** Delivery's own short writes: never a transaction held open while waiting on a push service. */
	private final TransactionTemplate write;

	private final Clock clock;

	PushService(PushSubscriptionRepository subscriptions, PushPreferenceRepository preferences, ObjectProvider<PushSender> sender,
			AppPushTokenRepository appTokens, ObjectProvider<AppPushSender> appSender, ObjectMapper json, PlatformTransactionManager transactions,
			Clock clock) {
		this.subscriptions = subscriptions;
		this.preferences = preferences;
		this.sender = sender;
		this.appTokens = appTokens;
		this.appSender = appSender;
		this.json = json;
		this.write = new TransactionTemplate(transactions);
		this.write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.clock = clock;
	}

	/**
	 * @param publicKey what browsers subscribe with; null when phone notifications aren't set up here
	 * @param muted     the kinds kept off this person's phones
	 * @param devices   how many of their browsers and phones with the app get them
	 */
	public record Settings(String publicKey, List<String> muted, long devices) {
	}

	/** push.settings */
	@Transactional(readOnly = true)
	public Settings settings(UUID me) {
		var push = sender.getIfAvailable();
		return new Settings(push == null ? null : push.publicKey(), muted(me), subscriptions.countByUserId(me) + appTokens.countByUserId(me));
	}

	/**
	 * push.registerDevice: this phone with the app, for the signed-in player. A phone someone else
	 * used moves to them, as a browser does.
	 */
	@Transactional
	public Settings registerDevice(UUID me, String token, String platform) {
		if (appSender.getIfAvailable() == null) {
			throw BusinessException.conflict("Phone notifications aren’t switched on for PlayChale yet.");
		}
		if (!AppPushSender.isToken(token)) {
			throw BusinessException.invalid("That isn’t the app’s notification token.");
		}
		if (!"android".equals(platform) && !"ios".equals(platform)) {
			throw BusinessException.invalid("Say which kind of phone: android or ios.");
		}
		appTokens.findByToken(token).ifPresentOrElse(t -> t.renew(me, platform),
				() -> appTokens.save(new AppPushToken(me, token, platform, clock.instant())));
		return settings(me);
	}

	/** push.forgetDevice: this phone stops getting them (signing out of the app, or turning them off). */
	@Transactional
	public Settings forgetDevice(UUID me, String token) {
		appTokens.deleteMine(me, token == null ? "" : token);
		return settings(me);
	}

	/** push.subscribe: this browser, for the signed-in player. A browser someone else used moves to them. */
	@Transactional
	public Settings subscribe(UUID me, String endpoint, String p256dh, String auth) {
		if (sender.getIfAvailable() == null) {
			throw BusinessException.conflict("Phone notifications aren’t switched on for PlayChale yet.");
		}
		var target = new PushTarget(endpoint, p256dh, auth);
		if (endpoint == null || !target.trusted()) {
			throw BusinessException.invalid("That isn’t a browser’s notification address.");
		}
		if (!target.wellFormed()) {
			throw BusinessException.invalid("That browser’s notification keys don’t look right.");
		}
		subscriptions.findByEndpoint(endpoint).ifPresentOrElse(s -> s.renew(me, p256dh, auth),
				() -> subscriptions.save(new PushSubscription(me, endpoint, p256dh, auth, clock.instant())));
		return settings(me);
	}

	/** push.unsubscribe: this browser stops getting them (signing out, or turning them off). */
	@Transactional
	public Settings unsubscribe(UUID me, String endpoint) {
		subscriptions.deleteMine(me, endpoint == null ? "" : endpoint);
		return settings(me);
	}

	/** push.mute: the kinds to keep off the phone. */
	@Transactional
	public Settings mute(UUID me, List<String> categories) {
		var chosen = categories == null ? List.<String>of() : categories.stream().distinct().toList();
		if (!PushCategories.ALL.containsAll(chosen)) {
			throw BusinessException.invalid("Pick from the kinds of notification.");
		}
		var preference = preferences.findById(me).orElseGet(() -> new PushPreference(me));
		preference.mute(chosen);
		preferences.save(preference);
		return settings(me);
	}

	/**
	 * A saved notification to each of the player's browsers and phones with the app, unless they keep
	 * that kind off them. Runs after it's committed.
	 */
	void deliver(NotificationSaved n) {
		var category = PushCategories.of(n.kind());
		if (category != null && muted(n.userId()).contains(category)) {
			return;
		}
		var link = n.link() == null ? "/me/notifications" : n.link();
		toApps(n, link);
		var push = sender.getIfAvailable();
		if (push == null) {
			return;
		}
		var phones = subscriptions.findByUserId(n.userId());
		if (phones.isEmpty()) {
			return;
		}
		var message = new LinkedHashMap<String, String>();
		message.put("title", n.title());
		message.put("body", n.body());
		message.put("link", link);
		message.put("tag", n.id().toString());
		var payload = json.writeValueAsString(message);
		for (var phone : phones) {
			switch (push.send(new PushTarget(phone.getEndpoint(), phone.getP256dh(), phone.getAuth()), payload)) {
				case GONE -> write.executeWithoutResult(t -> subscriptions.deleteById(phone.getId()));
				case SENT -> write.executeWithoutResult(t -> subscriptions.findById(phone.getId()).ifPresent(p -> p.sent(clock.instant())));
				case FAILED -> {
				}
			}
		}
	}

	private void toApps(NotificationSaved n, String link) {
		var app = appSender.getIfAvailable();
		if (app == null) {
			return;
		}
		for (var phone : appTokens.findByUserId(n.userId())) {
			switch (app.send(phone.getToken(), n.title(), n.body(), link)) {
				case GONE -> write.executeWithoutResult(t -> appTokens.deleteById(phone.getId()));
				case SENT -> write.executeWithoutResult(t -> appTokens.findById(phone.getId()).ifPresent(p -> p.sent(clock.instant())));
				case FAILED -> {
				}
			}
		}
	}

	private List<String> muted(UUID userId) {
		return preferences.findById(userId).map(PushPreference::getMuted).orElseGet(List::of);
	}

}
