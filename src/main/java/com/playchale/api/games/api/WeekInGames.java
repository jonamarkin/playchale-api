package com.playchale.api.games.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What happened in games lately, for telling people about it: who was voted player of the match, and how much was played. */
public interface WeekInGames {

	/**
	 * A player of the match vote that closed with a winner.
	 *
	 * @param playerKey the winner's roster key: a player's ID, or "guest:<spot>" for someone without an account
	 */
	record PlayerOfTheMatch(UUID gameId, String title, String country, String venueArea, String playerKey) {
	}

	/** How much was played in one country: games with a result, and the people on a side in them. */
	record Played(int games, int players) {
	}

	/** Votes that closed from {@code since} until {@code now}, newest game first; level winners are each listed. */
	List<PlayerOfTheMatch> playersOfTheMatchBetween(Instant since, Instant now);

	/** Games with a result that kicked off from {@code since} on, by country. */
	Map<String, Played> playedSince(Instant since);

}
