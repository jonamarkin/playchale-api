package com.playchale.api.profiles.internal.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One played game from a player's side: a row in their match history, as the web app's MatchRecord. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MatchRecord(UUID gameId, String title, String sport, String format, Instant startsAt, String venueName,
		String outcome, int scoreFor, int scoreAgainst, int goals, int assists, int points, List<SetScore> sets) {

	/** One set, this player's side first. */
	public record SetScore(int home, int away) {
	}

}
