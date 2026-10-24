package com.playchale.api.notifications.internal.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import com.playchale.api.auth.api.Presence;
import com.playchale.api.games.api.OpenGames;
import com.playchale.api.shared.config.PlaychaleProperties;
import com.playchale.api.shared.scheduling.ClusterLock;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.users.api.UserSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Still playing?": someone who hasn't opened PlayChale for a fortnight is told about the open games
 * near them this week, as a notification (and a push, under "Games near you") and, for those who
 * agreed to hear from PlayChale, an email. Nobody is told "nothing's on": with no game near them,
 * nothing is sent. Never by text.
 *
 * <p>Once, then not again for two months (come_back_nudges), however often it runs. Not someone who
 * joined less than a fortnight ago: they haven't gone quiet, they've just arrived. Daily, mid-morning.
 */
@Component
class ComeBackNudge {

	private static final Logger log = LoggerFactory.getLogger(ComeBackNudge.class);

	/** How long without a visit before they're asked. */
	static final Duration AWAY = Duration.ofDays(14);

	/** How long before anyone is asked a second time. */
	static final Duration AGAIN = Duration.ofDays(60);

	/** How many games the email lists; the rest are a line and the link. */
	static final int LISTED = 5;

	private final Presence presence;

	private final OpenGames open;

	private final UserDirectory users;

	private final NotificationService notifications;

	private final EmailService email;

	private final Mailouts mailouts;

	private final PlaychaleProperties properties;

	private final ClusterLock lock;

	private final JdbcClient jdbc;

	private final Clock clock;

	ComeBackNudge(Presence presence, OpenGames open, UserDirectory users, NotificationService notifications, EmailService email,
			Mailouts mailouts, PlaychaleProperties properties, ClusterLock lock, JdbcClient jdbc, Clock clock) {
		this.presence = presence;
		this.open = open;
		this.users = users;
		this.notifications = notifications;
		this.email = email;
		this.mailouts = mailouts;
		this.properties = properties;
		this.lock = lock;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Scheduled(cron = "0 0 10 * * *", zone = "Africa/Accra")
	@Transactional
	public void sendDaily() {
		if (!lock.tryLock("come-back-nudge")) {
			return;
		}
		send();
	}

	/** Nudges everyone who's been away with something near them. Returns how many were. Visible for testing. */
	int send() {
		var now = clock.instant();
		var since = now.minus(AWAY);
		var seen = presence.seenSince(since);
		var games = open.between(now, now.plus(Duration.ofDays(7)));
		var byEmail = new HashSet<>(email.weekendAudience());
		var mail = new ArrayList<Mailouts.Copy>();
		int nudged = 0;
		for (var member : users.members()) {
			if (seen.contains(member.id()) || member.createdAt().isAfter(since)) {
				continue;
			}
			var picks = WeekendNudge.picksFor(member, games);
			if (picks.isEmpty() || !claim(member.id(), picks.size(), now)) {
				continue;
			}
			nudged++;
			var title = picks.size() == 1 ? "Still playing? A game near you this week" : "Still playing? %d games near you this week".formatted(picks.size());
			notifications.send(member.id(), "nearby", title, WeekendNudge.line(picks.getFirst()) + (picks.size() > 1 ? " · and %d more".formatted(picks.size() - 1) : ""),
					"/games", null);
			if (byEmail.contains(member.id())) {
				var copy = copyFor(member, picks);
				if (copy != null) {
					mail.add(copy);
				}
			}
		}
		if (!mail.isEmpty()) {
			mailouts.send(new Mailouts.Letter("weekend", "weekend", "Still playing? Games near you this week", "Open games you could join this week."),
					null, mail);
		}
		if (nudged > 0) {
			log.info("Come-back nudge: {} nudged, {} by email", nudged, mail.size());
		}
		return nudged;
	}

	/** Takes this person's nudge: false when they've had one within two months. */
	private boolean claim(UUID userId, int games, Instant now) {
		return jdbc.sql("""
				INSERT INTO come_back_nudges (user_id, sent_at, games) VALUES (:user, :now, :games)
				ON CONFLICT (user_id) DO UPDATE SET sent_at = EXCLUDED.sent_at, games = EXCLUDED.games
				WHERE come_back_nudges.sent_at <= :again
				""").param("user", userId).param("now", now.atOffset(ZoneOffset.UTC)).param("games", games)
			.param("again", now.minus(AGAIN).atOffset(ZoneOffset.UTC)).update() == 1;
	}

	private Mailouts.Copy copyFor(UserSummary member, List<OpenGames.OpenGame> picks) {
		var address = EmailService.addressOf(member);
		if (address == null) {
			return null;
		}
		var first = member.name() == null || member.name().isBlank() ? null : member.name().strip().split("\\s+")[0];
		var token = email.tokenOf(member.id()).orElse(null);
		var values = new LinkedHashMap<String, String>();
		values.put("greeting", first == null ? "Still playing?" : "Still playing, %s?".formatted(first));
		values.put("games", String.join("\n", picks.stream().limit(LISTED).map(WeekendNudge::line).toList()));
		values.put("more", picks.size() > LISTED ? "And %d more on PlayChale.".formatted(picks.size() - LISTED) : "");
		values.put("actionUrl", properties.webApp() + "/games");
		return new Mailouts.Copy(member.id(), address, values, token == null ? null : "%s/unsubscribe?token=%s".formatted(properties.webApp(), token));
	}

}
