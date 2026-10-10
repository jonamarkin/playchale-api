package com.playchale.api.notifications.internal.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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

	/**
	 * Talk about a game reaches the others in it. A message nobody sees is no use for the thing this
	 * is actually for: "running ten minutes late", said an hour before kick-off.
	 */
	@EventListener
	void on(GameEvents.MessagePosted e) {
		var who = firstName(e.said());
		var link = "/games/%s".formatted(e.game().gameId());
		for (var recipient : e.recipients()) {
			notifications.send(recipient, "game-message", "%s said something about %s".formatted(who, e.game().title()), e.body(), link,
					e.said());
		}
	}

	@EventListener
	void on(GameEvents.PitchBooked e) {
		if (e.venueOwnerId() == null || e.venueOwnerId().equals(e.game().hostId())) {
			return;
		}
		notifications.send(e.venueOwnerId(), "booking", "%s booked %s".formatted(firstName(e.game().hostId()), e.pitchName()),
				"%s · %s · %s".formatted(e.game().title(), kickoff(e.game()), e.game().venueName()),
				"/venues/%s/manage".formatted(e.venueId()), e.game().hostId());
	}

	@EventListener
	void on(GameEvents.PlayerJoined e) {
		var game = e.game();
		var who = firstName(e.playerId());
		var link = "/games/%s".formatted(game.gameId());
		if (e.claimedGuestSpot()) {
			notifications.send(game.hostId(), "player-joined", "%s claimed their spot in %s".formatted(who, game.title()),
					"%s · %s".formatted(filled(e.filled(), e.capacity()), kickoff(game)), link, e.playerId());
		}
		else if (e.capacity() != null && e.filled() >= e.capacity()) {
			notifications.send(game.hostId(), "game-full", "%s is full".formatted(game.title()),
					"%s took the last spot. All %d players are in.".formatted(who, e.capacity()), link, e.playerId());
		}
		else {
			notifications.send(game.hostId(), "player-joined", "%s joined %s".formatted(who, game.title()),
					"%s · %s".formatted(filled(e.filled(), e.capacity()), kickoff(game)), link, e.playerId());
		}
	}

	/** "6 of 10 spots filled", or "12 going" for a game with no limit. */
	private static String filled(int filled, Integer capacity) {
		return capacity == null ? "%d going".formatted(filled) : "%d of %d spots filled".formatted(filled, capacity);
	}

	/** A guest took a spot: the host is told, as for any player, and that they came without an account. */
	@EventListener
	void on(GameEvents.GuestJoined e) {
		var game = e.game();
		var link = "/games/%s".formatted(game.gameId());
		if (e.capacity() != null && e.filled() >= e.capacity()) {
			notifications.send(game.hostId(), "game-full", "%s is full".formatted(game.title()),
					"%s took the last spot, as a guest. All %d players are in.".formatted(e.guestName(), e.capacity()), link, null);
		}
		else {
			notifications.send(game.hostId(), "player-joined", "%s joined %s as a guest".formatted(e.guestName(), game.title()),
					"%s · %s".formatted(filled(e.filled(), e.capacity()), kickoff(game)), link, null);
		}
	}

	@EventListener
	void on(GameEvents.PlayerRemoved e) {
		var game = e.game();
		notifications.send(e.playerId(), "removed-from-game", "You’re no longer in %s".formatted(game.title()),
				"%s removed you from this game, %s. Nothing was charged.".formatted(firstName(game.hostId()), kickoff(game)),
				"/games", game.hostId());
	}

	@EventListener
	void on(GameEvents.GameCalledOff e) {
		var game = e.game();
		var host = firstName(game.hostId());
		var note = e.reason() == null ? "" : " “%s”".formatted(e.reason());
		for (var player : e.playerIds()) {
			notifications.send(player, "game-cancelled", "%s is off".formatted(game.title()),
					"%s called off the game, %s.%s".formatted(host, kickoff(game), note), "/games", game.hostId());
		}
		if (e.venueOwnerId() != null && !e.venueOwnerId().equals(game.hostId())) {
			notifications.send(e.venueOwnerId(), "booking", "%s released %s".formatted(host, e.pitchName() == null ? "a pitch" : e.pitchName()),
					"%s was called off · %s · the slot is free again".formatted(game.title(), kickoff(game)),
					"/venues/%s/manage".formatted(e.venueId()), game.hostId());
		}
	}

	/** The host changed a game: everyone in it hears what's new, and a venue whose pitch booking moved hears that. */
	@EventListener
	void on(GameEvents.GameChanged e) {
		var game = e.game();
		var host = firstName(game.hostId());
		var moved = e.time() && e.place() ? "%s moved it to %s, %s.".formatted(host, game.venueName(), kickoff(game))
				: e.time() ? "%s moved it to %s. Same place: %s.".formatted(host, kickoff(game), game.venueName())
				: e.place() ? "%s moved it to %s. Same time: %s.".formatted(host, game.venueName(), kickoff(game))
				: "";
		var cost = !e.money() ? "" : e.share() == 0 ? "It’s free now." : "It’s %s each now.".formatted(money(game, e.share()));
		var title = e.time() || e.place() ? "%s has moved".formatted(game.title()) : "%s: the cost changed".formatted(game.title());
		var body = moved.isEmpty() ? "%s changed what each player pays. %s".formatted(host, cost) : (moved + " " + cost).strip();
		for (var player : e.playerIds()) {
			notifications.send(player, "game-moved", title, body, "/games/%s".formatted(game.gameId()), game.hostId());
		}
		if (e.venueOwnerId() != null && !e.venueOwnerId().equals(game.hostId())) {
			notifications.send(e.venueOwnerId(), "booking", "%s moved their booking".formatted(host),
					"%s · %s · %s".formatted(game.title(), e.pitchName() == null ? "a pitch" : e.pitchName(), kickoff(game)),
					"/venues/%s/manage".formatted(e.venueId()), game.hostId());
		}
	}

	/** The venue moved a game: everyone in it hears where and when it is now. */
	@EventListener
	void on(GameEvents.GameMoved e) {
		var game = e.game();
		var timeChanged = !e.from().equals(game.startsAt());
		var pitchChanged = e.fromPitch() == null || !e.fromPitch().equals(e.pitchName());
		var body = timeChanged && pitchChanged ? "%s moved it to %s, %s.".formatted(game.venueName(), e.pitchName(), kickoff(game))
				: timeChanged ? "%s moved it to %s, still on %s.".formatted(game.venueName(), kickoff(game), e.pitchName())
				: "%s moved it to %s. Same time: %s.".formatted(game.venueName(), e.pitchName(), kickoff(game));
		for (var player : e.playerIds()) {
			notifications.send(player, "game-moved", "%s has moved".formatted(game.title()), body, "/games/%s".formatted(game.gameId()), null);
		}
	}

	@EventListener
	void on(GameEvents.PlayersInvited e) {
		var game = e.game();
		var host = firstName(e.invitedBy());
		var cost = e.share() > 0 ? " · %s each".formatted(money(game, e.share())) : " · free";
		// A repeating game's next one asks its regulars itself; "Kojo invited you" would be untrue.
		var title = e.regulars() ? "%s is on again".formatted(game.title())
				: e.teamName() == null ? "%s invited you to %s".formatted(host, game.title())
				: "%s invited %s to %s".formatted(host, e.teamName(), game.title());
		for (var player : e.playerIds()) {
			notifications.send(player, "game-invite", title,
					"%s · %s%s. Say if you’re in.".formatted(kickoff(game), game.venueName(), cost), "/games/%s".formatted(game.gameId()),
					e.invitedBy());
		}
	}

	/** The host of a repeating game hears that its next game is up, and who was asked. */
	@EventListener
	void on(GameEvents.SeriesGameOpened e) {
		var game = e.game();
		var body = e.invited() == 0 ? "Nobody from last time to invite. Share the link with your players."
				: e.invited() == 1 ? "1 player from last time is invited." : "%d players from last time are invited.".formatted(e.invited());
		notifications.send(game.hostId(), "series", "%s is set for %s".formatted(game.title(), kickoff(game)), body,
				"/games/%s".formatted(game.gameId()), null);
	}

	/** A date a repeating game couldn't open, and why. The host may want to set that one up somewhere else. */
	@EventListener
	void on(GameEvents.SeriesDateMissed e) {
		var when = Market.get(e.country()).formatKickoff(e.startsAt(), clock.instant(), ZoneId.of(e.timezone()));
		notifications.send(e.hostId(), "series", "%s skips %s".formatted(e.title(), when),
				"%s It carries on with the date after.".formatted(e.reason()), "/me/games", null);
	}

	@EventListener
	void on(GameEvents.SeriesPaused e) {
		notifications.send(e.hostId(), "series", "%s is paused".formatted(e.title()),
				"%s Restart it from My games when you’re ready.".formatted(e.reason()), "/me/games", null);
	}

	@EventListener
	void on(GameEvents.ChallengeSent e) {
		var game = e.game();
		notifications.send(e.awayCaptainId(), "game-invite", "%s challenged %s".formatted(e.homeTeam(), e.awayTeam()),
				"%s · %s · %s. Accept or decline from the game.".formatted(game.title(), kickoff(game), game.venueName()),
				"/games/%s".formatted(game.gameId()), game.hostId());
	}

	@EventListener
	void on(GameEvents.ChallengeAnswered e) {
		var game = e.game();
		var link = "/games/%s".formatted(game.gameId());
		if (e.accepted()) {
			notifications.send(game.hostId(), "squad-reply", "%s accepted your challenge".formatted(e.awayTeam()),
					"%s · %s. Their players are being asked who’s in.".formatted(game.title(), kickoff(game)), link, e.captainId());
		}
		else {
			notifications.send(game.hostId(), "squad-reply", "%s can’t play".formatted(e.awayTeam()),
					"They turned down %s, %s.".formatted(game.title(), kickoff(game)), link, e.captainId());
		}
	}

	@EventListener
	void on(GameEvents.InviteDeclined e) {
		var game = e.game();
		notifications.send(game.hostId(), "invite-declined", "%s can’t make %s".formatted(firstName(e.playerId()), game.title()),
				"They said no to your invite for %s.".formatted(kickoff(game)), "/games/%s".formatted(game.gameId()), e.playerId());
	}

	@EventListener
	void on(GameEvents.PaymentReminded e) {
		var game = e.game();
		var host = firstName(game.hostId());
		for (var player : e.playerIds()) {
			// A share of a total, or the price to take part: each said as what it is.
			var title = e.perPlayer() ? "Pay %s for %s".formatted(money(game, e.share()), game.title())
					: "Pay your %s share".formatted(money(game, e.share()));
			notifications.send(player, "payment-reminder", title,
					"%s is collecting for %s, %s.".formatted(host, game.title(), kickoff(game)),
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
		// Paying in the app is only in Ghana, so this is always cedis.
		notifications.send(e.hostId(), "payment-received", "%s paid %s".formatted(firstName(e.payerId()), Market.get(Market.DEFAULT).formatMoney(e.amount())),
				"%s · %d of %d paid".formatted(e.gameTitle(), e.paid(), e.players()), "/games/%s".formatted(e.gameId()), e.payerId());
	}

	private String firstName(UUID userId) {
		return users.find(userId).map(u -> u.name().split(" ")[0]).filter(n -> !n.isBlank()).orElse("Someone");
	}

	/** Kick-off in the game's own local time: where it's played. */
	private String kickoff(GameEvents.GameInfo game) {
		return Market.get(game.country()).formatKickoff(game.startsAt(), clock.instant(), ZoneId.of(game.timezone()));
	}

	/** Money the game's country's way: "GH₵ 25", "£ 5". */
	private static String money(GameEvents.GameInfo game, long amount) {
		return Market.get(game.country()).formatMoney(amount);
	}

}
