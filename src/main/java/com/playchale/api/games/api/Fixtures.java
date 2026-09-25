package com.playchale.api.games.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * League fixtures are ordinary games, so results, stats and notifications work the same. This is how
 * the competitions module creates them and keeps their rosters in step with the squads.
 */
public interface Fixtures {

	/**
	 * One fixture to create. The organiser hosts it; everyone in the two squads has a spot, and
	 * nobody pays through the app for a league game.
	 *
	 * @param venueKind "listed" (with {@code venueId}) or "unlisted"
	 */
	record FixtureSpec(UUID competitionId, int round, UUID homeTeamId, UUID awayTeamId, String title, String sport, String format,
			Instant startsAt, int durationMinutes, String venueKind, UUID venueId, String venueName, String venueArea, UUID organiserId,
			List<UUID> squad) {
	}

	/**
	 * A fixture in brief, for league tables.
	 *
	 * @param homeScore null until it has a result
	 */
	record FixtureSummary(UUID gameId, int round, UUID homeTeamId, UUID awayTeamId, Instant startsAt, String status, Integer homeScore,
			Integer awayScore) {

		public boolean played() {
			return homeScore != null && !"cancelled".equals(status);
		}

	}

	void create(List<FixtureSpec> fixtures);

	/**
	 * Gives a fixture that hasn't been played this squad: new players get a spot, players who left
	 * lose theirs. Fixtures already played, under way or called off are left alone.
	 */
	void syncSquad(UUID gameId, List<UUID> squad);

	List<FixtureSummary> of(UUID competitionId);

	/** A league's fixtures in kick-off order, as the games they are. */
	List<GameResponse> views(UUID competitionId, UUID viewer);

}
