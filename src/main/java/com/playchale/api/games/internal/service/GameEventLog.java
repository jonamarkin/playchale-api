package com.playchale.api.games.internal.service;

import java.util.Map;

import com.playchale.api.games.api.GameEvents;
import com.playchale.api.shared.events.Happened;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Writes what happens to a game into the event stream.
 *
 * <p>One listener rather than a line in every service: the modules already publish these events for
 * notifications, so the facts are there to be kept and nothing in the domain has to know it is being
 * recorded.
 *
 * <p>What is here is chosen for the questions state cannot answer later. A game's row ends up saying
 * "completed" or "cancelled" and nothing else: how long it took to fill, whether it filled before it
 * was called off, how often a pitch booking became a played game — all of that lives only in the
 * order these events arrived, and only if they were written down at the time.
 *
 * <p>Nothing personal beyond the ids the subject rows already hold, so an account closing takes its
 * events with it.
 */
@Component
class GameEventLog {

	private static final String GAME = "game";

	private static final String SERIES = "game-series";

	private final Happened happened;

	GameEventLog(Happened happened) {
		this.happened = happened;
	}

	@EventListener
	void on(GameEvents.PlayerJoined e) {
		// filled and capacity are the whole point: with the time, they give how fast a game fills.
		happened.record("game.joined", GAME, e.game().gameId(), e.playerId(),
				Map.of("filled", e.filled(), "capacity", e.capacity(), "full", e.filled() >= e.capacity(),
						"claimedGuestSpot", e.claimedGuestSpot()));
	}

	@EventListener
	void on(GameEvents.PlayerRemoved e) {
		happened.record("game.player-removed", GAME, e.game().gameId(), e.game().hostId(), Map.of());
	}

	@EventListener
	void on(GameEvents.GameCalledOff e) {
		happened.record("game.called-off", GAME, e.game().gameId(), e.game().hostId(),
				Map.of("players", e.playerIds().size(), "hadReason", e.reason() != null && !e.reason().isBlank(),
						"atVenue", e.venueId() != null));
	}

	@EventListener
	void on(GameEvents.PitchBooked e) {
		happened.record("game.pitch-booked", GAME, e.game().gameId(), e.game().hostId(),
				Map.of("venueId", e.venueId().toString(), "pitch", e.pitchName()));
	}

	@EventListener
	void on(GameEvents.GameMoved e) {
		happened.record("game.moved", GAME, e.game().gameId(), null,
				Map.of("from", e.from().toString(), "to", e.game().startsAt().toString(), "players", e.playerIds().size()));
	}

	@EventListener
	void on(GameEvents.ResultRecorded e) {
		happened.record("game.result", GAME, e.game().gameId(), e.game().hostId(),
				Map.of("corrected", e.corrected(), "inSets", e.inSets(), "homeScore", e.homeScore(), "awayScore", e.awayScore()));
	}

	@EventListener
	void on(GameEvents.ResultDisputed e) {
		happened.record("game.result-disputed", GAME, e.game().gameId(), e.playerId(), Map.of());
	}

	@EventListener
	void on(GameEvents.CashShareCollected e) {
		happened.record("game.share-collected", GAME, e.game().gameId(), e.game().hostId(),
				Map.of("amount", e.amount(), "currency", e.currency(), "method", "cash"));
	}

	/** Repeating games: with game.joined, how many of a regular game's players keep coming back. */
	@EventListener
	void on(GameEvents.SeriesGameOpened e) {
		happened.record("series.opened", SERIES, e.seriesId(), e.game().hostId(),
				Map.of("gameId", e.game().gameId().toString(), "startsAt", e.game().startsAt().toString(), "invited", e.invited()));
	}

	@EventListener
	void on(GameEvents.SeriesDateMissed e) {
		happened.record("series.date-missed", SERIES, e.seriesId(), null, Map.of("startsAt", e.startsAt().toString(), "reason", e.reason()));
	}

	@EventListener
	void on(GameEvents.SeriesPaused e) {
		happened.record("series.paused", SERIES, e.seriesId(), null, Map.of("reason", e.reason()));
	}

	@EventListener
	void on(GameEvents.SeriesUpdated e) {
		happened.record("series." + e.change(), SERIES, e.seriesId(), e.hostId(), Map.of());
	}

	@EventListener
	void on(GameEvents.SeriesOptOut e) {
		happened.record(e.out() ? "series.opted-out" : "series.opted-in", SERIES, e.seriesId(), e.userId(), Map.of());
	}

}
