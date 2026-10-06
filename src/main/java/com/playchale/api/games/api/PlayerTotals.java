package com.playchale.api.games.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Totals across many players at once, worked out in the database.
 *
 * <p>Separate from {@link PlayedGames}, which loads one player's games and adds them up in Java.
 * That is right for a profile and impossible for a table of players: a leaderboard would mean
 * loading every result of every player to sort the top twenty.
 */
public interface PlayerTotals {

	/**
	 * What a leaderboard can be sorted by. Totals, never rates: a rate off two games is noise.
	 *
	 * <p>Not every sport keeps every one. Football counts goals and assists, basketball counts
	 * points, and volleyball and tennis count no player stats at all — they are played in sets, so
	 * SETS is the only thing of their own they have. Wins and games mean something everywhere.
	 */
	enum Metric {

		GOALS, ASSISTS, POINTS, SETS, WINS, GAMES

	}

	/**
	 * Which games count.
	 *
	 * @param sport   a catalogue sport id, or null for all of them
	 * @param country ISO 3166-1 alpha-2, or null for everywhere
	 * @param area    the part of town a game was played in, matched loosely; null for everywhere
	 * @param since   only games kicked off after this, or null for all time
	 */
	record Filter(String sport, String country, String area, Instant since, Metric metric, int limit) {
	}

	/** One player's totals over the games the filter allowed. */
	record Totals(UUID userId, int games, int wins, int goals, int assists, int points, int sets) {
	}

	/** The players at the top, best first. Only verified results count, as a profile's record does. */
	List<Totals> top(Filter filter);

	/** The areas that have games with results, for the filter to offer. */
	List<String> areas(String country);

}
