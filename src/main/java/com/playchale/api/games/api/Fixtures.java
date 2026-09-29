package com.playchale.api.games.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
	 * @param venueKind "listed" (with {@code venueId}) or "unlisted" (maybe with a {@code venueMapUrl})
	 * @param country   the league's country (ISO 3166-1)
	 * @param timezone  the league's local time (IANA)
	 */
	record FixtureSpec(UUID competitionId, int round, UUID homeTeamId, UUID awayTeamId, String title, String sport, String format,
			Instant startsAt, int durationMinutes, String venueKind, UUID venueId, String venueName, String venueArea, String venueMapUrl,
			UUID organiserId, List<UUID> squad, String country, String timezone, Integer slot, boolean decider) {

		/** A league fixture: no place in a bracket, and it may end level. */
		public FixtureSpec(UUID competitionId, int round, UUID homeTeamId, UUID awayTeamId, String title, String sport, String format,
				Instant startsAt, int durationMinutes, String venueKind, UUID venueId, String venueName, String venueArea, String venueMapUrl,
				UUID organiserId, List<UUID> squad, String country, String timezone) {
			this(competitionId, round, homeTeamId, awayTeamId, title, sport, format, startsAt, durationMinutes, venueKind, venueId, venueName,
					venueArea, venueMapUrl, organiserId, squad, country, timezone, null, false);
		}
	}

	/**
	 * A fixture in brief, for league tables.
	 *
	 * @param homeScore null until it has a result
	 */
	record FixtureSummary(UUID gameId, int round, UUID homeTeamId, UUID awayTeamId, Instant startsAt, String status, Integer homeScore,
			Integer awayScore, Integer slot, Integer homePenalties, Integer awayPenalties) {

		public boolean played() {
			return homeScore != null && !"cancelled".equals(status);
		}

		/**
		 * Who goes through, in a tie that has to produce a winner: the higher score, or the shootout
		 * when it ended level. Null while it's still level, or not played.
		 */
		public UUID winner() {
			if (!played()) {
				return null;
			}
			if (homeScore > awayScore) {
				return homeTeamId;
			}
			if (awayScore > homeScore) {
				return awayTeamId;
			}
			if (homePenalties == null || homePenalties.equals(awayPenalties)) {
				return null;
			}
			return homePenalties > awayPenalties ? homeTeamId : awayTeamId;
		}

	}

	/**
	 * What one player has done across a league's fixtures. {@code teamId} is the squad they're in, or
	 * null in a league that keeps no player lists.
	 */
	record Scorer(UUID userId, UUID teamId, int goals, int assists, int points, int games) {
	}

	void create(List<FixtureSpec> fixtures);

	/**
	 * Gives a fixture that hasn't been played this squad: new players get a spot, players who left
	 * lose theirs. Fixtures already played, under way or called off are left alone.
	 */
	void syncSquad(UUID gameId, List<UUID> squad);

	List<FixtureSummary> of(UUID competitionId);

	/** The competition a game is a fixture of, if it is one. */
	Optional<UUID> competitionOf(UUID gameId);

	/** Everyone who scored in a league's fixtures, most first. Empty for a sport with no player stats. */
	List<Scorer> scorers(UUID competitionId, String sport);

	/** A league's fixtures in kick-off order, as the games they are. */
	List<GameResponse> views(UUID competitionId, UUID viewer);

}
