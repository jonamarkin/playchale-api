package com.playchale.api.competitions.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A person's week in the competitions they're part of, for the weekly round-up. Declared here so
 * the notifications module can ask without knowing anything about how competitions are stored.
 */
public interface CompetitionDigest {

	/**
	 * One result from a competition they are in — a squad they play for, or one they run.
	 *
	 * @param theirs the name of the team they belong to, or null when they only organise it
	 */
	record CompetitionResult(UUID competitionId, String competition, String homeTeam, String awayTeam, int homeScore, int awayScore,
			Instant playedAt, String theirs) {
	}

	/** Results recorded since {@code since}, in the competitions this person is in. Newest first. */
	List<CompetitionResult> since(UUID userId, Instant since);

	/** Fixtures still to be played in those competitions, soonest first, within {@code until}. */
	List<CompetitionResult> upcoming(UUID userId, Instant until);

}
