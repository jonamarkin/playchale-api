package com.playchale.api.notifications.internal.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import com.playchale.api.games.api.EndedGames;
import com.playchale.api.games.api.EndedGames.Ended;
import com.playchale.api.shared.scheduling.ClusterLock;
import com.playchale.api.users.api.UserDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A game's score is what gives everyone their stats and starts the player of the match vote, and
 * only the host adds it. So the host is asked: once, half an hour after the game ends ("How did it
 * go?"), and once more the next morning from 9 am if it's still missing. Then no more. In the app
 * and by push (the "Games" setting), never by text.
 *
 * <p>Once per game whatever runs twice (result_reminders). Not a game called off, nor a
 * competition's fixture, whose organisers have their own flow, nor a guest host's, who has no bell.
 */
@Component
class ResultReminders {

	private static final Logger log = LoggerFactory.getLogger(ResultReminders.class);

	/** How long after the final whistle the first ask goes. */
	static final Duration AFTER_END = Duration.ofMinutes(30);

	/** The next morning's ask goes from this hour, in the game's own time. */
	static final int MORNING_HOUR = 9;

	/** Games that ended longer ago than this are left alone: the moment has passed. */
	static final Duration GIVE_UP = Duration.ofHours(40);

	private final EndedGames ended;

	private final UserDirectory users;

	private final NotificationService notifications;

	private final ClusterLock lock;

	private final JdbcClient jdbc;

	private final Clock clock;

	ResultReminders(EndedGames ended, UserDirectory users, NotificationService notifications, ClusterLock lock, JdbcClient jdbc, Clock clock) {
		this.ended = ended;
		this.users = users;
		this.notifications = notifications;
		this.lock = lock;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Scheduled(cron = "0 */15 * * * *")
	@Transactional
	public void sendDue() {
		if (!lock.tryLock("result-reminders")) {
			return;
		}
		send();
	}

	/** Asks every host who's due an ask. Returns how many asks went. Visible for testing. */
	int send() {
		var now = clock.instant();
		int sent = 0;
		for (var game : ended.endedBetween(now.minus(GIVE_UP), now.minus(AFTER_END))) {
			var host = users.find(game.hostId()).orElse(null);
			if (host == null || Boolean.TRUE.equals(host.guestHost())) {
				continue;
			}
			var link = "/games/%s".formatted(game.id());
			if (claimFirst(game, now)) {
				notifications.send(game.hostId(), "game-reminder", "How did it go? · " + game.title(),
						"Add the score: everyone in it gets their stats, and the player of the match vote opens.", link, null);
				sent++;
			}
			else if (nextMorning(game, now) && claimSecond(game, now)) {
				notifications.send(game.hostId(), "game-reminder", "Still no score for " + game.title(),
						"Add it when you can. Stats and the player of the match wait on it.", link, null);
				sent++;
			}
		}
		if (sent > 0) {
			log.info("Asked {} host(s) for a score", sent);
		}
		return sent;
	}

	/** From 9 am the day after the game, in its own time, and not late at night. */
	private static boolean nextMorning(Ended game, Instant now) {
		var zone = ZoneId.of(game.timezone());
		var here = now.atZone(zone);
		return here.toLocalDate().isAfter(LocalDate.ofInstant(game.endsAt(), zone)) && here.getHour() >= MORNING_HOUR && here.getHour() < 21;
	}

	private boolean claimFirst(Ended game, Instant now) {
		return jdbc.sql("""
				INSERT INTO result_reminders (game_id, ended_at, first_sent_at) VALUES (:game, :ended, :now)
				ON CONFLICT (game_id) DO NOTHING
				""").param("game", game.id()).param("ended", game.endsAt().atOffset(ZoneOffset.UTC)).param("now", now.atOffset(ZoneOffset.UTC))
			.update() == 1;
	}

	private boolean claimSecond(Ended game, Instant now) {
		return jdbc.sql("UPDATE result_reminders SET second_sent_at = :now WHERE game_id = :game AND second_sent_at IS NULL")
			.param("game", game.id()).param("now", now.atOffset(ZoneOffset.UTC)).update() == 1;
	}

	/** Visible for testing: how many asks a game has had. */
	int asksFor(UUID gameId) {
		return jdbc.sql("SELECT CASE WHEN second_sent_at IS NULL THEN 1 ELSE 2 END FROM result_reminders WHERE game_id = :game")
			.param("game", gameId).query(Integer.class).optional().orElse(0);
	}

}
