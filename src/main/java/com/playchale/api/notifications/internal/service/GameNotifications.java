package com.playchale.api.notifications.internal.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.playchale.api.games.api.GameEvents;
import com.playchale.api.market.Market;
import com.playchale.api.users.api.UserDirectory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Turns what happens in games into notifications. The listeners run inside the game's own
 * transaction, so a change and its notifications are saved together or not at all. (Texts and
 * push messages that must survive a crash will go through an outbox instead.)
 */
@Component
class GameNotifications {

	private final NotificationService notifications;

	private final UserDirectory users;

	private final Clock clock;

	GameNotifications(NotificationService notifications, UserDirectory users, Clock clock) {
		this.notifications = notifications;
		this.users = users;
		this.clock = clock;
	}

	@EventListener
	void on(GameEvents.PitchBooked e) {
		if (e.venueOwnerId() == null || e.venueOwnerId().equals(e.game().hostId())) {
			return;
		}
		notifications.send(e.venueOwnerId(), "booking", "%s booked %s".formatted(firstName(e.game().hostId()), e.pitchName()),
				"%s · %s · %s".formatted(e.game().title(), kickoff(e.game().startsAt()), e.game().venueName()),
				"/venues/%s/manage".formatted(e.venueId()), e.game().hostId());
	}

	@EventListener
	void on(GameEvents.PlayerJoined e) {
		var game = e.game();
		var who = firstName(e.playerId());
		var link = "/games/%s".formatted(game.gameId());
		if (e.claimedGuestSpot()) {
			notifications.send(game.hostId(), "player-joined", "%s claimed their spot in %s".formatted(who, game.title()),
					"%d of %d spots filled · %s".formatted(e.filled(), e.capacity(), kickoff(game.startsAt())), link, e.playerId());
		}
		else if (e.filled() >= e.capacity()) {
			notifications.send(game.hostId(), "game-full", "%s is full".formatted(game.title()),
					"%s took the last spot. All %d players are in.".formatted(who, e.capacity()), link, e.playerId());
		}
		else {
			notifications.send(game.hostId(), "player-joined", "%s joined %s".formatted(who, game.title()),
					"%d of %d spots filled · %s".formatted(e.filled(), e.capacity(), kickoff(game.startsAt())), link, e.playerId());
		}
	}

	@EventListener
	void on(GameEvents.PlayerRemoved e) {
		var game = e.game();
		notifications.send(e.playerId(), "removed-from-game", "You’re no longer in %s".formatted(game.title()),
				"%s removed you from this game, %s. Nothing was charged.".formatted(firstName(game.hostId()), kickoff(game.startsAt())),
				"/games", game.hostId());
	}

	@EventListener
	void on(GameEvents.GameCalledOff e) {
		var game = e.game();
		var host = firstName(game.hostId());
		var note = e.reason() == null ? "" : " “%s”".formatted(e.reason());
		for (var player : e.playerIds()) {
			notifications.send(player, "game-cancelled", "%s is off".formatted(game.title()),
					"%s called off the game, %s.%s".formatted(host, kickoff(game.startsAt()), note), "/games", game.hostId());
		}
		if (e.venueOwnerId() != null && !e.venueOwnerId().equals(game.hostId())) {
			notifications.send(e.venueOwnerId(), "booking", "%s released %s".formatted(host, e.pitchName() == null ? "a pitch" : e.pitchName()),
					"%s was called off · %s · the slot is free again".formatted(game.title(), kickoff(game.startsAt())),
					"/venues/%s/manage".formatted(e.venueId()), game.hostId());
		}
	}

	@EventListener
	void on(GameEvents.PlayersInvited e) {
		var game = e.game();
		var host = firstName(game.hostId());
		var cost = e.share() > 0 ? " · %s each".formatted(market().formatMoney(e.share())) : " · free";
		for (var player : e.playerIds()) {
			notifications.send(player, "game-invite", "%s invited you to %s".formatted(host, game.title()),
					"%s · %s%s".formatted(kickoff(game.startsAt()), game.venueName(), cost), "/games/%s".formatted(game.gameId()),
					game.hostId());
		}
	}

	@EventListener
	void on(GameEvents.PaymentReminded e) {
		var game = e.game();
		var host = firstName(game.hostId());
		for (var player : e.playerIds()) {
			notifications.send(player, "payment-reminder", "Pay your %s share".formatted(market().formatMoney(e.share())),
					"%s is collecting for %s, %s.".formatted(host, game.title(), kickoff(game.startsAt())),
					"/games/%s?pay=1".formatted(game.gameId()), game.hostId());
		}
	}

	private String firstName(UUID userId) {
		return users.find(userId).map(u -> u.name().split(" ")[0]).filter(n -> !n.isBlank()).orElse("Someone");
	}

	private String kickoff(Instant at) {
		return market().formatKickoff(at, clock.instant());
	}

	private static Market market() {
		return Market.get(Market.DEFAULT);
	}

}
