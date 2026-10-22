package com.playchale.api.games.internal.service;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.playchale.api.games.api.GameEvents;
import com.playchale.api.games.internal.domain.ResultLine;
import com.playchale.api.games.internal.repository.GameRepository;
import com.playchale.api.games.internal.repository.GameResultRepository;
import com.playchale.api.games.internal.repository.ResultVotes;
import com.playchale.api.users.api.UserDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Once a game's player of the match vote has closed, the players hear who won: once, and only if
 * anyone picked. Every quarter of an hour. Safe with several copies of the API running: each game's
 * announcement is claimed before it's made, so a second copy finds it taken.
 */
@Component
class PlayerOfTheMatch {

	private static final Logger log = LoggerFactory.getLogger(PlayerOfTheMatch.class);

	private final ResultVotes votes;

	private final GameResultRepository results;

	private final GameRepository games;

	private final UserDirectory users;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	PlayerOfTheMatch(ResultVotes votes, GameResultRepository results, GameRepository games, UserDirectory users,
			ApplicationEventPublisher events, Clock clock) {
		this.votes = votes;
		this.results = results;
		this.games = games;
		this.users = users;
		this.events = events;
		this.clock = clock;
	}

	@Scheduled(cron = "0 */15 * * * *")
	@Transactional
	public void announceDue() {
		announce();
	}

	/** Announces every vote that's closed and not yet announced. Returns how many were. Visible for testing. */
	int announce() {
		var now = clock.instant();
		var due = votes.closedUnannounced(now);
		var picks = votes.ofGames(due);
		int announced = 0;
		for (var gameId : due) {
			var game = games.findById(gameId).orElse(null);
			var result = results.findById(gameId).orElse(null);
			if (game == null || result == null || !votes.claimAnnouncement(gameId, now)) {
				continue;
			}
			var tally = result.tally(picks.getOrDefault(gameId, List.of()), null, now).orElse(null);
			// Every pick was for someone the host has since taken off a side: nothing to say.
			if (tally == null || tally.winners().isEmpty()) {
				continue;
			}
			var played = result.getPlayers().stream().filter(ResultLine::played).map(ResultLine::userId).filter(Objects::nonNull).toList();
			var people = users.findAll(played);
			var names = tally.winners().stream().map(key -> game.getParticipants().stream()
				.filter(p -> p.playerKey().equals(key)).findFirst()
				.map(p -> p.isGuest() ? p.getGuestName() : people.containsKey(p.getUserId()) ? people.get(p.getUserId()).name() : null)
				.filter(n -> n != null && !n.isBlank()).map(n -> n.strip().split("\\s+")[0]).orElse("A player")).toList();
			var recipients = new LinkedHashSet<UUID>(played);
			recipients.add(game.getHostId());
			var winners = tally.winners().stream().filter(key -> !key.startsWith("guest:")).map(UUID::fromString).toList();
			events.publishEvent(new GameEvents.PlayerOfTheMatchChosen(GameService.info(game), names, List.copyOf(recipients), winners));
			announced++;
		}
		if (announced > 0) {
			log.info("Announced the player of the match in {} game(s)", announced);
		}
		return announced;
	}

}
