package com.playchale.api.notifications.internal.service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.playchale.api.games.api.GameEvents;
import com.playchale.api.integration.sms.SmsSender;
import com.playchale.api.shared.config.PlaychaleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * A weekly game's next one has opened: last time's members are invited in the app, but its guests
 * have no account to be invited with, so each Ghanaian number among them gets one text with the link
 * to join. Once the game is really there (after it commits), and never holding up the opening.
 * PLAYCHALE_REMINDERS=false stops it, as it stops the evening-before reminders.
 */
@Component
class SeriesTexts {

	private static final Logger log = LoggerFactory.getLogger(SeriesTexts.class);

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH);

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mma", Locale.ENGLISH);

	private final ObjectProvider<SmsSender> sms;

	private final PlaychaleProperties properties;

	private final boolean enabled;

	SeriesTexts(ObjectProvider<SmsSender> sms, PlaychaleProperties properties, @Value("${playchale.reminders:true}") boolean enabled) {
		this.sms = sms;
		this.properties = properties;
		this.enabled = enabled;
	}

	@TransactionalEventListener(fallbackExecution = true)
	void on(GameEvents.SeriesGameOpened e) {
		var sender = sms.getIfAvailable();
		var numbers = e.guestPhones().stream().filter(TextSettings::texted).distinct().toList();
		if (!enabled || sender == null || numbers.isEmpty()) {
			return;
		}
		var message = text(e.game(), properties.webApp());
		for (var phone : numbers) {
			try {
				sender.send(phone, message);
			}
			catch (RuntimeException failed) {
				log.warn("Couldn’t text a guest that game {} is on again: {}", e.game().gameId(), failed.toString());
			}
		}
	}

	/** "PlayChale: Saturday 5s is on again, Sat 6:00pm at Legon Park. Join: playchale.com/games/…", one SMS. */
	static String text(GameEvents.GameInfo game, String webApp) {
		var at = game.startsAt().atZone(ZoneId.of(game.timezone()));
		var when = DAY.format(at) + " " + TIME.format(at).toLowerCase(Locale.ENGLISH);
		var link = SmsText.link(webApp + "/games/" + game.gameId());
		return SmsText.fit("PlayChale: %s is on again, " + when + " at %s. Join: " + link, game.title(), game.venueName());
	}

}
