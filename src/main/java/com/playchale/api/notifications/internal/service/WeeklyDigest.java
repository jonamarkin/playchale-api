package com.playchale.api.notifications.internal.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.playchale.api.competitions.api.CompetitionDigest;
import com.playchale.api.games.api.OpenGames;
import com.playchale.api.games.api.PlayedGames;
import com.playchale.api.games.api.WeekInGames;
import com.playchale.api.shared.config.PlaychaleProperties;
import com.playchale.api.shared.scheduling.ClusterLock;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.users.api.UserSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The weekly round-up, for everyone who asked for one.
 *
 * <p>Every line is this person's own week, read back from what actually happened — their results,
 * their competitions' results, what they play next. There is no copy to write each week and so
 * nothing to get wrong, and someone whose week was empty is not emailed at all: an email that says
 * "you played no games and nothing happened" is worse than silence.
 *
 * <p>Monday morning, on one copy of the API at a time.
 */
@Component
class WeeklyDigest {

	private static final Logger log = LoggerFactory.getLogger("email");

	private static final Duration WEEK = Duration.ofDays(7);

	/** How far ahead "what's next" looks. A fortnight catches a league that plays every other week. */
	private static final Duration AHEAD = Duration.ofDays(14);

	private final EmailService email;

	private final Mailouts mailouts;

	private final PlayedGames played;

	private final CompetitionDigest competitions;

	private final UserDirectory users;

	private final WeekInGames week;

	private final OpenGames open;

	private final PlaychaleProperties properties;

	private final ClusterLock lock;

	private final Clock clock;

	WeeklyDigest(EmailService email, Mailouts mailouts, PlayedGames played, CompetitionDigest competitions, UserDirectory users,
			WeekInGames week, OpenGames open, PlaychaleProperties properties, ClusterLock lock, Clock clock) {
		this.email = email;
		this.mailouts = mailouts;
		this.played = played;
		this.competitions = competitions;
		this.users = users;
		this.week = week;
		this.open = open;
		this.properties = properties;
		this.lock = lock;
		this.clock = clock;
	}

	@Scheduled(cron = "0 0 7 * * MON", zone = "Africa/Accra")
	@Transactional
	public void sendWeekly() {
		if (!lock.tryLock("weekly-digest")) {
			return;
		}
		send();
	}

	/** Builds everyone's copy and hands the lot to {@link Mailouts}. Visible for testing. */
	UUID send() {
		var now = clock.instant();
		var since = now.minus(WEEK);
		var until = now.plus(AHEAD);
		// What happened around everyone, read once: who the players voted player of the match, how
		// much was played, and the open games for the week ahead.
		var around = new Around(week.playersOfTheMatchBetween(since, now), week.playedSince(since), open.between(now, now.plus(WEEK)), users);
		var copies = new ArrayList<Mailouts.Copy>();
		for (var userId : email.digestAudience()) {
			var values = weekOf(userId, since, until, around);
			if (values == null) {
				continue;
			}
			var user = users.find(userId).orElse(null);
			var token = email.tokenOf(userId).orElse(null);
			copies.add(new Mailouts.Copy(userId, EmailService.addressOf(user), values,
					token == null ? null : "%s/unsubscribe?token=%s".formatted(properties.webApp(), token)));
		}
		if (copies.isEmpty()) {
			log.info("Weekly digest: nobody had a week worth sending");
			return null;
		}
		return mailouts.send(new Mailouts.Letter("digest", "digest", "Your week on PlayChale", "Your results, and what's next."),
				null, copies);
	}

	/** This person's week as the template's values, or null when there is nothing to tell them. */
	private Map<String, String> weekOf(UUID userId, Instant since, Instant until, Around around) {
		var games = played.by(userId).stream().filter(g -> g.startsAt().isAfter(since)).toList();
		var results = competitions.since(userId, since);
		var next = competitions.upcoming(userId, until);
		var user = users.find(userId).orElse(null);
		var nearby = user == null ? "" : around.linesFor(user);
		if (games.isEmpty() && results.isEmpty() && next.isEmpty() && nearby.isEmpty()) {
			return null;
		}
		return Map.of("greeting", greeting(user == null ? null : user.name()), "week", weekLabel(since),
				"yours", yours(games), "competitions", competitionLines(results, next),
				"around", nearby.isEmpty() ? "A quiet week near you." : nearby, "actionUrl", properties.webApp() + "/home");
	}

	/**
	 * The week around a person: in their country, who the players voted player of the match (first
	 * names, as the game page shows them) and how much was played, then the open games near them for
	 * the week ahead. Everything here is already public on game pages and profiles.
	 */
	static final class Around {

		private final List<WeekInGames.PlayerOfTheMatch> winners;

		private final Map<String, WeekInGames.Played> played;

		private final List<OpenGames.OpenGame> openGames;

		private final Map<UUID, UserSummary> names;

		Around(List<WeekInGames.PlayerOfTheMatch> winners, Map<String, WeekInGames.Played> played, List<OpenGames.OpenGame> openGames,
				UserDirectory users) {
			this.winners = winners;
			this.played = played;
			this.openGames = openGames;
			var ids = winners.stream().map(WeekInGames.PlayerOfTheMatch::playerKey).filter(k -> !k.startsWith("guest:")).map(UUID::fromString).toList();
			this.names = ids.isEmpty() ? Map.of() : users.findAll(ids);
		}

		/** Lines for one person, or empty when there's nothing in their country and nothing near them. */
		String linesFor(UserSummary member) {
			var country = member.country() == null || member.country().isBlank() ? com.playchale.api.market.Market.DEFAULT : member.country();
			var lines = new ArrayList<String>();
			var count = played.get(country);
			if (count != null && count.games() > 0) {
				lines.add("%d %s played by %d people in %s this week.".formatted(count.games(), count.games() == 1 ? "game" : "games", count.players(),
						Locale.of("", country).getDisplayCountry(Locale.UK)));
			}
			winners.stream().filter(w -> country.equals(w.country())).limit(3).forEach(w -> {
				var who = names.get(w.playerKey().startsWith("guest:") ? null : UUID.fromString(w.playerKey()));
				if (who != null && who.name() != null && !who.name().isBlank()) {
					lines.add("%s · player of the match in %s".formatted(who.name().strip().split("\\s+")[0], w.title()));
				}
			});
			var picks = WeekendNudge.picksFor(member, openGames);
			if (!picks.isEmpty()) {
				lines.add(lines.isEmpty() ? "Near you this week:" : "\nNear you this week:");
				picks.stream().limit(3).forEach(g -> lines.add(WeekendNudge.line(g)));
				if (picks.size() > 3) {
					lines.add("And %d more on PlayChale.".formatted(picks.size() - 3));
				}
			}
			return String.join("\n", lines);
		}

	}

	private static String greeting(String name) {
		var first = name == null || name.isBlank() ? null : name.trim().split("\\s+")[0];
		return first == null ? "Your week" : "Your week, " + first;
	}

	private String weekLabel(Instant since) {
		var zone = ZoneId.of("Africa/Accra");
		var format = DateTimeFormatter.ofPattern("d MMM", Locale.UK);
		return "%s – %s".formatted(format.format(since.atZone(zone)), format.format(clock.instant().atZone(zone)));
	}

	/** Their own games, as lines. Counted rather than listed, because a long list isn't read. */
	private static String yours(List<PlayedGames.PlayedGame> games) {
		if (games.isEmpty()) {
			return "You didn’t play this week.";
		}
		var wins = games.stream().filter(g -> "W".equals(g.outcome())).count();
		var goals = games.stream().mapToInt(PlayedGames.PlayedGame::goals).sum();
		var lines = new ArrayList<String>();
		lines.add("%d %s, %d won.".formatted(games.size(), games.size() == 1 ? "game" : "games", wins));
		if (goals > 0) {
			lines.add("%d %s scored.".formatted(goals, goals == 1 ? "goal" : "goals"));
		}
		games.stream().limit(3).forEach(g -> lines.add("%s · %d–%d".formatted(g.title(), g.scoreFor(), g.scoreAgainst())));
		return String.join("\n", lines);
	}

	/** Results from their competitions, then what they play next. */
	private String competitionLines(List<CompetitionDigest.CompetitionResult> results, List<CompetitionDigest.CompetitionResult> next) {
		var lines = new ArrayList<String>();
		results.stream().limit(5).forEach(r -> lines
			.add("%s · %s %d–%d %s".formatted(r.competition(), r.homeTeam(), r.homeScore(), r.awayScore(), r.awayTeam())));
		var zone = ZoneId.of("Africa/Accra");
		var when = DateTimeFormatter.ofPattern("EEE d MMM, h:mma", Locale.UK);
		next.stream().limit(3).forEach(f -> lines
			.add("Next: %s v %s · %s".formatted(f.homeTeam(), f.awayTeam(), when.format(f.playedAt().atZone(zone)).toLowerCase(Locale.UK))));
		return lines.isEmpty() ? "Nothing from your competitions this week." : String.join("\n", lines);
	}

}
