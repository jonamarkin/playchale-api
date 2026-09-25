package com.playchale.api.notifications.internal.service;

import java.time.Clock;
import java.util.UUID;

import com.playchale.api.competitions.api.CompetitionEvents;
import com.playchale.api.market.Market;
import com.playchale.api.users.api.UserDirectory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Turns what happens in competitions into notifications, inside the change's own transaction. */
@Component
class CompetitionNotifications {

	private final NotificationService notifications;

	private final UserDirectory users;

	private final Clock clock;

	CompetitionNotifications(NotificationService notifications, UserDirectory users, Clock clock) {
		this.notifications = notifications;
		this.users = users;
		this.clock = clock;
	}

	@EventListener
	void on(CompetitionEvents.AddedToSquad e) {
		for (var player : e.playerIds()) {
			notifications.send(player, "squad-reply", "You’re in %s".formatted(e.teamName()),
					"%s added you to the squad for %s.".formatted(firstName(e.by()), e.league().name()), link(e.league()), e.by());
		}
	}

	@EventListener
	void on(CompetitionEvents.SquadRequested e) {
		notifications.send(e.captainId(), "squad-request", "%s wants to play for %s".formatted(firstName(e.playerId()), e.teamName()),
				"%s. You can say yes or no from the league page.".formatted(e.league().name()), link(e.league()), e.playerId());
	}

	@EventListener
	void on(CompetitionEvents.SquadAnswered e) {
		var by = firstName(e.by());
		if (e.accepted()) {
			notifications.send(e.playerId(), "squad-reply", "You’re in %s".formatted(e.teamName()),
					"%s said yes. Your fixtures are in My games.".formatted(by), link(e.league()), e.by());
		}
		else {
			notifications.send(e.playerId(), "squad-reply", "%s is full for now".formatted(e.teamName()),
					"%s couldn’t fit you in for %s. Other teams may have room.".formatted(by, e.league().name()), link(e.league()), e.by());
		}
	}

	@EventListener
	void on(CompetitionEvents.JoinedByLink e) {
		notifications.send(e.captainId(), "squad-reply", "%s joined %s".formatted(firstName(e.playerId()), e.teamName()),
				"%d in the squad for %s.".formatted(e.squadSize(), e.league().name()), link(e.league()), e.playerId());
	}

	@EventListener
	void on(CompetitionEvents.FixturesDrawn e) {
		var kickoff = Market.get(Market.DEFAULT).formatKickoff(e.startsAt(), clock.instant());
		for (var player : e.playerIds()) {
			notifications.send(player, "game-invite", "%s: fixtures are out".formatted(e.league().name()),
					"%d rounds, starting %s.".formatted(e.rounds(), kickoff), link(e.league()), e.organiserId());
		}
	}

	private static String link(CompetitionEvents.LeagueInfo league) {
		return "/competitions/%s".formatted(league.competitionId());
	}

	private String firstName(UUID userId) {
		return users.find(userId).map(u -> u.name().split(" ")[0]).filter(n -> !n.isBlank()).orElse("Someone");
	}

}
