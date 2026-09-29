package com.playchale.api.notifications.internal.service;

import java.time.Clock;
import java.time.ZoneId;
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
					"%s added you to the squad for %s.".formatted(firstName(e.by()), e.competition().name()), link(e.competition()), e.by());
		}
	}

	@EventListener
	void on(CompetitionEvents.SquadRequested e) {
		notifications.send(e.captainId(), "squad-request", "%s wants to play for %s".formatted(firstName(e.playerId()), e.teamName()),
				"%s. You can say yes or no from the %s page.".formatted(e.competition().name(), e.competition().noun()), link(e.competition()),
				e.playerId());
	}

	@EventListener
	void on(CompetitionEvents.SquadAnswered e) {
		var by = firstName(e.by());
		if (e.accepted()) {
			notifications.send(e.playerId(), "squad-reply", "You’re in %s".formatted(e.teamName()),
					"%s said yes. Your fixtures are in My games.".formatted(by), link(e.competition()), e.by());
		}
		else {
			notifications.send(e.playerId(), "squad-reply", "%s is full for now".formatted(e.teamName()),
					"%s couldn’t fit you in for %s. Other teams may have room.".formatted(by, e.competition().name()), link(e.competition()), e.by());
		}
	}

	@EventListener
	void on(CompetitionEvents.JoinedByLink e) {
		notifications.send(e.captainId(), "squad-reply", "%s joined %s".formatted(firstName(e.playerId()), e.teamName()),
				"%d in the squad for %s.".formatted(e.squadSize(), e.competition().name()), link(e.competition()), e.playerId());
	}

	@EventListener
	void on(CompetitionEvents.TeamInvited e) {
		notifications.send(e.captainId(), "squad-request", "%s invited %s".formatted(e.competition().name(), e.teamName()),
				"%s would like your team in. Accept or decline from the %s page.".formatted(firstName(e.organiserId()),
						e.competition().noun()),
				link(e.competition()), e.organiserId());
	}

	@EventListener
	void on(CompetitionEvents.EntryAnswered e) {
		notifications.send(e.organiserId(), "squad-reply",
				e.accepted() ? "%s is in %s".formatted(e.teamName(), e.competition().name()) : "%s won’t play in %s".formatted(e.teamName(), e.competition().name()),
				e.accepted() ? "%s accepted your invitation.".formatted(firstName(e.captainId())) : "%s declined your invitation.".formatted(firstName(e.captainId())),
				link(e.competition()), e.captainId());
	}

	@EventListener
	void on(CompetitionEvents.FixturesDrawn e) {
		// In its own local time: where it's played.
		var competition = e.competition();
		var kickoff = Market.get(competition.country())
			.formatKickoff(e.startsAt(), clock.instant(), ZoneId.of(competition.timezone()));
		// A league draws every round at once; a tournament draws the first and the rest follow the results.
		var cup = "tournament".equals(competition.noun());
		var headline = "%s: %s".formatted(competition.name(), cup ? "the draw is out" : "fixtures are out");
		var body = cup ? "%d rounds to the final, starting %s.".formatted(e.rounds(), kickoff)
				: "%d rounds, starting %s.".formatted(e.rounds(), kickoff);
		for (var player : e.playerIds()) {
			notifications.send(player, "game-invite", headline, body, link(e.competition()), e.organiserId());
		}
	}

	private static String link(CompetitionEvents.CompetitionInfo competition) {
		return "/competitions/%s".formatted(competition.competitionId());
	}

	private String firstName(UUID userId) {
		return users.find(userId).map(u -> u.name().split(" ")[0]).filter(n -> !n.isBlank()).orElse("Someone");
	}

}
