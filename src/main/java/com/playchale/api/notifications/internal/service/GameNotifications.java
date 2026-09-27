package com.playchale.api.notifications.internal.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.playchale.api.games.api.GameEvents;
import com.playchale.api.market.Market;
import com.playchale.api.payments.api.PaymentReceived;
import com.playchale.api.users.api.UserDirectory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Turns what happens in games (and their payments) into notifications. The listeners run inside the game's own
 * transaction, so a change and its notifications are saved together or not at all. (Texts and
 * push messages that must survive a crash will go through an outbox instead.)
 */
@Component
class GameNotifications {

	private static final Map<String, String> OUTCOME = Map.of("W", "won", "D", "drew", "L", "lost");

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

	/** The venue moved a game: everyone in it hears where and when it is now. */
	@EventListener
	void on(GameEvents.GameMoved e) {
		var game = e.game();
		var timeChanged = !e.from().equals(game.startsAt());
		var pitchChanged = e.fromPitch() == null || !e.fromPitch().equals(e.pitchName());
		var body = timeChanged && pitchChanged ? "%s moved it to %s, %s.".formatted(game.venueName(), e.pitchName(), kickoff(game.startsAt()))
				: timeChanged ? "%s moved it to %s, still on %s.".formatted(game.venueName(), kickoff(game.startsAt()), e.pitchName())
				: "%s moved it to %s. Same time: %s.".formatted(game.venueName(), e.pitchName(), kickoff(game.startsAt()));
		for (var player : e.playerIds()) {
			notifications.send(player, "game-moved", "%s has moved".formatted(game.title()), body, "/games/%s".formatted(game.gameId()), null);
		}
	}

	@EventListener
	void on(GameEvents.PlayersInvited e) {
		var game = e.game();
		var host = firstName(e.invitedBy());
		var cost = e.share() > 0 ? " · %s each".formatted(market().formatMoney(e.share())) : " · free";
		var title = e.teamName() == null ? "%s invited you to %s".formatted(host, game.title())
				: "%s invited %s to %s".formatted(host, e.teamName(), game.title());
		for (var player : e.playerIds()) {
			notifications.send(player, "game-invite", title,
					"%s · %s%s. Say if you’re in.".formatted(kickoff(game.startsAt()), game.venueName(), cost), "/games/%s".formatted(game.gameId()),
					e.invitedBy());
		}
	}

	@EventListener
	void on(GameEvents.ChallengeSent e) {
		var game = e.game();
		notifications.send(e.awayCaptainId(), "game-invite", "%s challenged %s".formatted(e.homeTeam(), e.awayTeam()),
				"%s · %s · %s. Accept or decline from the game.".formatted(game.title(), kickoff(game.startsAt()), game.venueName()),
				"/games/%s".formatted(game.gameId()), game.hostId());
	}

	@EventListener
	void on(GameEvents.ChallengeAnswered e) {
		var game = e.game();
		var link = "/games/%s".formatted(game.gameId());
		if (e.accepted()) {
			notifications.send(game.hostId(), "squad-reply", "%s accepted your challenge".formatted(e.awayTeam()),
					"%s · %s. Their players are being asked who’s in.".formatted(game.title(), kickoff(game.startsAt())), link, e.captainId());
		}
		else {
			notifications.send(game.hostId(), "squad-reply", "%s can’t play".formatted(e.awayTeam()),
					"They turned down %s, %s.".formatted(game.title(), kickoff(game.startsAt())), link, e.captainId());
		}
	}

	@EventListener
	void on(GameEvents.InviteDeclined e) {
		var game = e.game();
		notifications.send(game.hostId(), "invite-declined", "%s can’t make %s".formatted(firstName(e.playerId()), game.title()),
				"They said no to your invite for %s.".formatted(kickoff(game.startsAt())), "/games/%s".formatted(game.gameId()), e.playerId());
	}

	@EventListener
	void on(GameEvents.PaymentReminded e) {
		var game = e.game();
		var host = firstName(game.hostId());
		for (var player : e.playerIds()) {
			// A share of a total, or the price to take part: each said as what it is.
			var title = e.perPlayer() ? "Pay %s for %s".formatted(market().formatMoney(e.share()), game.title())
					: "Pay your %s share".formatted(market().formatMoney(e.share()));
			notifications.send(player, "payment-reminder", title,
					"%s is collecting for %s, %s.".formatted(host, game.title(), kickoff(game.startsAt())),
					"/games/%s?pay=1".formatted(game.gameId()), game.hostId());
		}
	}

	@EventListener
	void on(GameEvents.ResultRecorded e) {
		var game = e.game();
		var sets = e.inSets() ? " in sets" : "";
		var title = "%s: %s".formatted(e.corrected() ? "Result corrected" : "Result", game.title());
		for (var player : e.players()) {
			var body = player.outcome() == null
					? "%s added the score: %d–%d%s.".formatted(firstName(game.hostId()), e.homeScore(), e.awayScore(), sets)
					: "You %s %d–%d%s. Your stats are updated.".formatted(OUTCOME.get(player.outcome()), player.scoreFor(),
							player.scoreAgainst(), sets);
			notifications.send(player.playerId(), "result-added", title, body, "/games/%s".formatted(game.gameId()), game.hostId());
		}
	}

	@EventListener
	void on(GameEvents.ResultDisputed e) {
		var game = e.game();
		var note = e.reason() == null ? "" : " · “%s”".formatted(e.reason());
		notifications.send(game.hostId(), "result-disputed", "%s says the result isn’t right".formatted(firstName(e.playerId())),
				"%s%s. You can correct it.".formatted(game.title(), note), "/games/%s".formatted(game.gameId()), e.playerId());
	}

	@EventListener
	void on(PaymentReceived e) {
		notifications.send(e.hostId(), "payment-received", "%s paid %s".formatted(firstName(e.payerId()), market().formatMoney(e.amount())),
				"%s · %d of %d paid".formatted(e.gameTitle(), e.paid(), e.players()), "/games/%s".formatted(e.gameId()), e.payerId());
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
