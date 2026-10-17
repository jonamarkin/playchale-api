package com.playchale.api.notifications.internal.service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.playchale.api.games.api.OpenGames;
import com.playchale.api.market.Market;
import com.playchale.api.shared.config.PlaychaleProperties;
import com.playchale.api.shared.scheduling.ClusterLock;
import com.playchale.api.users.api.UserSummary;
import com.playchale.api.users.api.UserDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * "This weekend near you", on Friday evening when people plan their weekend: the open games this
 * weekend in each person's sports and places, read from what's really on, as a notification (and a
 * push, unless they keep "Games near you" off their phone) and, for those who agreed to hear from
 * PlayChale, an email.
 *
 * <p>Nobody hears "nothing's on". Someone with no games near them gets nothing, except a host who
 * put a game on lately and has nothing this weekend, who is asked whether to put one up. One nudge a
 * weekend at most, whatever runs twice (weekend_nudges).
 *
 * <p>"Near" is what Home's "Games near you" means: their country, nudged to their sports and the
 * places they play. Someone who's said neither hears about every game in their country.
 */
@Component
class WeekendNudge {

	private static final Logger log = LoggerFactory.getLogger(WeekendNudge.class);

	/** A host who put a game on within this long is worth asking. */
	static final Duration HOSTED_LATELY = Duration.ofDays(90);

	/** How many games the email lists; the rest are a line and the link. */
	static final int LISTED = 5;

	private static final ZoneId ACCRA = ZoneId.of("Africa/Accra");

	private final OpenGames open;

	private final UserDirectory users;

	private final NotificationService notifications;

	private final EmailService email;

	private final Mailouts mailouts;

	private final PlaychaleProperties properties;

	private final ClusterLock lock;

	private final JdbcClient jdbc;

	private final Clock clock;

	WeekendNudge(OpenGames open, UserDirectory users, NotificationService notifications, EmailService email, Mailouts mailouts,
			PlaychaleProperties properties, ClusterLock lock, JdbcClient jdbc, Clock clock) {
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

	@Scheduled(cron = "0 0 17 * * FRI", zone = "Africa/Accra")
	@Transactional
	public void sendWeekly() {
		if (!lock.tryLock("weekend-nudge")) {
			return;
		}
		send();
	}

	/** Nudges everyone with something to hear this weekend. Returns how many were. Visible for testing. */
	int send() {
		var now = clock.instant();
		var saturday = LocalDate.ofInstant(now, ACCRA).with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
		var from = saturday.atStartOfDay(ACCRA).toInstant();
		var to = saturday.plusDays(2).atStartOfDay(ACCRA).toInstant();
		// Run late, on the Saturday itself: from now, not from games already started.
		if (now.isAfter(from)) {
			from = now;
		}
		var games = open.between(from, to);
		var hosts = open.hostedSince(now.minus(HOSTED_LATELY), now);
		var busy = open.hosting(from, to);
		var byEmail = new HashSet<>(email.weekendAudience());
		var gamesMail = new ArrayList<Mailouts.Copy>();
		var startMail = new ArrayList<Mailouts.Copy>();
		int nudged = 0;
		for (var member : users.members()) {
			var picks = picksFor(member, games);
			var start = picks.isEmpty() && hosts.contains(member.id()) && !busy.contains(member.id());
			if (picks.isEmpty() && !start) {
				continue;
			}
			if (!claim(member.id(), saturday, start ? "start" : "games", picks.size(), now)) {
				continue;
			}
			nudged++;
			if (start) {
				notifications.send(member.id(), "nearby", "Nothing on near you this weekend",
						"Put a game up: a place, a time, and a link for the group chat.", "/games/new", null);
			}
			else {
				notifications.send(member.id(), "nearby", title(picks.size()), line(picks.getFirst()) + more(picks.size(), 1), "/games?when=weekend", null);
			}
			if (byEmail.contains(member.id())) {
				var copy = copyFor(member, start ? null : picks);
				if (copy != null) {
					(start ? startMail : gamesMail).add(copy);
				}
			}
		}
		if (!gamesMail.isEmpty()) {
			mailouts.send(new Mailouts.Letter("weekend", "weekend", "Games near you this weekend", "Open games you could join this weekend."), null,
					gamesMail);
		}
		if (!startMail.isEmpty()) {
			mailouts.send(new Mailouts.Letter("weekend", "weekend-start", "Nothing on near you this weekend?",
					"Put a game up: a place, a time, and a link for the group chat."), null, startMail);
		}
		log.info("Weekend nudge: {} nudged, {} by email", nudged, gamesMail.size() + startMail.size());
		return nudged;
	}

	/** The open games for this person: in their country, not ones they're in, nudged to their sports and places. */
	static List<OpenGames.OpenGame> picksFor(UserSummary member, List<OpenGames.OpenGame> games) {
		var country = member.country() == null || member.country().isBlank() ? Market.DEFAULT : member.country();
		var sports = Set.copyOf(member.sports());
		var places = (member.areas().isEmpty() ? (member.area() == null ? List.<String>of() : List.of(member.area())) : member.areas()).stream()
			.map(place -> place.split(",")[0].strip().toLowerCase(Locale.ROOT)).filter(place -> !place.isEmpty()).toList();
		var choosy = !sports.isEmpty() || !places.isEmpty();
		record Scored(OpenGames.OpenGame game, int score) {
		}
		return games.stream()
			.filter(g -> country.equals(g.country()) && !g.playerIds().contains(member.id()))
			.map(g -> new Scored(g, (sports.contains(g.sport()) ? 2 : 0) + (near(g, places) ? 1 : 0)))
			.filter(s -> !choosy || s.score() > 0)
			.sorted(Comparator.comparingInt((Scored s) -> -s.score()).thenComparing(s -> s.game().startsAt()))
			.map(Scored::game)
			.toList();
	}

	private static boolean near(OpenGames.OpenGame game, List<String> places) {
		var where = ((game.venueArea() == null ? "" : game.venueArea()) + " " + game.venueName()).toLowerCase(Locale.ROOT);
		return places.stream().anyMatch(where::contains);
	}

	/** Takes this weekend's nudge for them: false when they've had it already. */
	private boolean claim(UUID userId, LocalDate weekend, String kind, int games, Instant now) {
		return jdbc.sql("""
				INSERT INTO weekend_nudges (user_id, weekend, kind, games, sent_at) VALUES (:user, :weekend, :kind, :games, :now)
				ON CONFLICT (user_id, weekend) DO NOTHING
				""").param("user", userId).param("weekend", weekend).param("kind", kind).param("games", games)
			.param("now", now.atOffset(ZoneOffset.UTC)).update() == 1;
	}

	private static String title(int games) {
		return games == 1 ? "A game near you this weekend" : "%d games near you this weekend".formatted(games);
	}

	/** "Saturday 5s · Sat 4:30 pm · Halfway Line Turf · 2 spots left". */
	static String line(OpenGames.OpenGame game) {
		var zone = game.timezone() == null ? ACCRA : ZoneId.of(game.timezone());
		var when = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.UK).format(game.startsAt().atZone(zone)).replace("AM", "am").replace("PM", "pm");
		var spots = game.spotsLeft() == null ? "" : " · %d %s left".formatted(game.spotsLeft(), game.spotsLeft() == 1 ? "spot" : "spots");
		return "%s · %s · %s%s".formatted(game.title(), when, game.venueName(), spots);
	}

	private static String more(int games, int shown) {
		return games > shown ? " · and %d more".formatted(games - shown) : "";
	}

	/** Their email: the games listed (or, with none, the ask to put one up), and the link that stops it. */
	private Mailouts.Copy copyFor(UserSummary member, List<OpenGames.OpenGame> picks) {
		var address = EmailService.addressOf(member);
		if (address == null) {
			return null;
		}
		var first = member.name() == null || member.name().isBlank() ? null : member.name().strip().split("\\s+")[0];
		var token = email.tokenOf(member.id()).orElse(null);
		var unsubscribe = token == null ? null : "%s/unsubscribe?token=%s".formatted(properties.webApp(), token);
		// In reading order: the plain-text copy is these values, one after another.
		var values = new LinkedHashMap<String, String>();
		if (picks == null) {
			values.put("greeting", first == null ? "Nothing on near you this weekend?" : "Nothing on this weekend, " + first + "?");
			values.put("actionUrl", properties.webApp() + "/games/new");
			return new Mailouts.Copy(member.id(), address, values, unsubscribe);
		}
		var heading = title(picks.size());
		values.put("greeting", first == null ? heading : "%s, %s".formatted(first, heading.substring(0, 1).toLowerCase(Locale.ROOT) + heading.substring(1)));
		values.put("games", String.join("\n", picks.stream().limit(LISTED).map(WeekendNudge::line).toList()));
		values.put("more", picks.size() > LISTED ? "And %d more on PlayChale.".formatted(picks.size() - LISTED) : "");
		values.put("actionUrl", properties.webApp() + "/games?when=weekend");
		return new Mailouts.Copy(member.id(), address, values, unsubscribe);
	}

}
