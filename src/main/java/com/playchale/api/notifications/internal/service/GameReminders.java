package com.playchale.api.notifications.internal.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import com.playchale.api.games.api.UpcomingGames;
import com.playchale.api.games.api.UpcomingGames.Upcoming;
import com.playchale.api.integration.sms.SmsSender;
import com.playchale.api.shared.config.PlaychaleProperties;
import com.playchale.api.shared.scheduling.ClusterLock;
import com.playchale.api.users.api.UserDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The evening before a game, everyone in it is reminded: in the app (and a push, unless they keep
 * "Games" off their phone), and by text to every Ghanaian number in it, guests included, who can't be
 * reached any other way. A member can turn the texts off ({@link TextSettings}).
 *
 * <p>From 6 pm in the game's own time until 9 pm, so a game set up at 7 pm for tomorrow is reminded
 * too, and nobody is texted late at night. Once per kick-off, whatever runs twice (game_reminders); a
 * game moved to another day is reminded again for that day. Not a game called off, nor a
 * competition's, whose organisers tell their own teams.
 *
 * <p>A text costs money, so each is one SMS: plain characters (a curly quote would halve how many fit)
 * and 160 of them at most. PLAYCHALE_REMINDERS=false stops the whole thing.
 */
@Component
class GameReminders {

	private static final Logger log = LoggerFactory.getLogger(GameReminders.class);

	/** From when, in the game's own time, the evening before. */
	static final int FROM_HOUR = 18;

	/** Until when: nobody is texted after this. */
	static final int UNTIL_HOUR = 21;

	/** One SMS. */
	static final int SMS_LENGTH = SmsText.LENGTH;

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

	private final UpcomingGames upcoming;

	private final UserDirectory users;

	private final NotificationService notifications;

	private final TextSettings texts;

	private final ObjectProvider<SmsSender> sms;

	private final PlaychaleProperties properties;

	private final ClusterLock lock;

	private final JdbcClient jdbc;

	private final Clock clock;

	private final boolean enabled;

	GameReminders(UpcomingGames upcoming, UserDirectory users, NotificationService notifications, TextSettings texts,
			ObjectProvider<SmsSender> sms, PlaychaleProperties properties, ClusterLock lock, JdbcClient jdbc, Clock clock,
			@Value("${playchale.reminders:true}") boolean enabled) {
		this.upcoming = upcoming;
		this.users = users;
		this.notifications = notifications;
		this.texts = texts;
		this.sms = sms;
		this.properties = properties;
		this.lock = lock;
		this.jdbc = jdbc;
		this.clock = clock;
		this.enabled = enabled;
	}

	@Scheduled(cron = "0 */15 * * * *")
	@Transactional
	public void sendDue() {
		if (!enabled || !lock.tryLock("game-reminders")) {
			return;
		}
		send();
	}

	/** Reminds every game due one now. Returns how many games were. Visible for testing. */
	int send() {
		var now = clock.instant();
		int reminded = 0;
		int texted = 0;
		// Tomorrow is at most two days away, wherever a game is played.
		for (var game : upcoming.startingBetween(now, now.plus(Duration.ofHours(54)))) {
			var zone = ZoneId.of(game.timezone());
			var here = now.atZone(zone);
			if (here.getHour() < FROM_HOUR || here.getHour() >= UNTIL_HOUR
					|| !LocalDate.ofInstant(game.startsAt(), zone).equals(here.toLocalDate().plusDays(1))) {
				continue;
			}
			if (!claim(game, now)) {
				continue;
			}
			reminded++;
			texted += remind(game, zone);
		}
		if (reminded > 0) {
			log.info("Reminded {} game(s) of tomorrow, with {} text(s)", reminded, texted);
		}
		return reminded;
	}

	/** Takes this kick-off's reminder, unless it's been sent. */
	private boolean claim(Upcoming game, Instant now) {
		return jdbc.sql("""
				INSERT INTO game_reminders (game_id, starts_at, sent_at) VALUES (:game, :starts, :now)
				ON CONFLICT (game_id) DO UPDATE SET starts_at = EXCLUDED.starts_at, sent_at = EXCLUDED.sent_at
				WHERE game_reminders.starts_at <> EXCLUDED.starts_at
				""").param("game", game.id()).param("starts", game.startsAt().atOffset(ZoneOffset.UTC)).param("now", now.atOffset(ZoneOffset.UTC))
			.update() == 1;
	}

	/** Returns how many texts went. */
	private int remind(Upcoming game, ZoneId zone) {
		var time = TIME.format(game.startsAt().atZone(zone)).toLowerCase(Locale.ENGLISH);
		var link = "/games/%s".formatted(game.id());
		var numbers = new LinkedHashSet<String>();
		var people = users.findAll(game.playerIds());
		for (var id : game.playerIds()) {
			var person = people.get(id);
			if (person == null) {
				continue;
			}
			// A guest host sees only their own games, never the bell: a text is how they hear.
			if (Boolean.TRUE.equals(person.guestHost())) {
				add(numbers, person.guestPhone());
				continue;
			}
			var body = id.equals(game.hostId())
					? "%s at %s · %s in.".formatted(time, game.venueName(), game.capacity() == null ? game.filled() : "%d of %d".formatted(game.filled(), game.capacity()))
					: "%s at %s. See you there.".formatted(time, game.venueName());
			notifications.send(id, "game-reminder", "Tomorrow: " + game.title(), body, link, null);
			if (texts.remindsByText(id)) {
				add(numbers, person.phone());
			}
		}
		game.guestPhones().forEach(phone -> add(numbers, phone));
		var sender = sms.getIfAvailable();
		if (sender == null || numbers.isEmpty()) {
			return 0;
		}
		var message = text(game.title(), time, game.venueName(), properties.webApp() + link);
		int sent = 0;
		for (var phone : numbers) {
			try {
				sender.send(phone, message);
				sent++;
			}
			catch (RuntimeException failed) {
				log.warn("Couldn’t text a reminder for game {}: {}", game.id(), failed.toString());
			}
		}
		return sent;
	}

	private static void add(Set<String> numbers, String phone) {
		if (TextSettings.texted(phone)) {
			numbers.add(phone);
		}
	}

	/** The text: "PlayChale: Saturday 5s is tomorrow, 6:00pm at Legon Park. Details: playchale.com/games/…", one SMS. */
	static String text(String title, String time, String venue, String url) {
		return SmsText.fit("PlayChale: %s is tomorrow, " + time.replace(" ", "") + " at %s. Details: " + SmsText.link(url), title, venue);
	}

}
