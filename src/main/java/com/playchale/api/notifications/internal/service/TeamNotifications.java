package com.playchale.api.notifications.internal.service;

import java.util.UUID;

import com.playchale.api.teams.api.TeamEvents;
import com.playchale.api.users.api.UserDirectory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Turns what happens in teams into notifications, inside the change's own transaction. Only for
 * changes made on the team itself: a league tells its own story about squads.
 */
@Component
class TeamNotifications {

	private final NotificationService notifications;

	private final UserDirectory users;

	TeamNotifications(NotificationService notifications, UserDirectory users) {
		this.notifications = notifications;
		this.users = users;
	}

	@EventListener
	void on(TeamEvents.MemberJoined e) {
		if (!e.announce()) {
			return;
		}
		if (e.userId().equals(e.addedBy())) {
			// Joined by the team's link: the captain hears.
			if (!e.userId().equals(e.captainId())) {
				notifications.send(e.captainId(), "squad-reply", "%s joined %s".formatted(firstName(e.userId()), e.teamName()),
						"They joined from the team’s link.", link(e.teamId()), e.userId());
			}
			return;
		}
		notifications.send(e.userId(), "squad-reply", "You’re in %s".formatted(e.teamName()),
				"%s added you to the team.".formatted(firstName(e.addedBy())), link(e.teamId()), e.addedBy());
	}

	@EventListener
	void on(TeamEvents.JoinRequested e) {
		if (e.announce()) {
			notifications.send(e.captainId(), "squad-request", "%s wants to join %s".formatted(firstName(e.userId()), e.teamName()),
					"You can say yes or no from the team’s page.", link(e.teamId()), e.userId());
		}
	}

	@EventListener
	void on(TeamEvents.RequestAnswered e) {
		if (!e.announce()) {
			return;
		}
		if (e.accepted()) {
			notifications.send(e.userId(), "squad-reply", "You’re in %s".formatted(e.teamName()),
					"%s said yes.".formatted(firstName(e.captainId())), link(e.teamId()), e.captainId());
		}
		else {
			notifications.send(e.userId(), "squad-reply", "%s is full for now".formatted(e.teamName()),
					"%s couldn’t fit you in.".formatted(firstName(e.captainId())), link(e.teamId()), e.captainId());
		}
	}

	private static String link(UUID teamId) {
		return "/teams/%s".formatted(teamId);
	}

	private String firstName(UUID userId) {
		return users.find(userId).map(u -> u.name().split(" ")[0]).filter(n -> !n.isBlank()).orElse("Someone");
	}

}
