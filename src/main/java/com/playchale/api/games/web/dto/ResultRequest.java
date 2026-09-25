package com.playchale.api.games.web.dto;

import java.util.List;

import com.playchale.api.games.internal.domain.ResultInput;
import com.playchale.api.games.internal.domain.SetScore;
import jakarta.validation.constraints.NotNull;

/** The web app's ResultInput. Players are named by the roster's keys: a player's ID, or "guest:<token>". */
public record ResultRequest(int homeScore, int awayScore, @NotNull(message = "Put at least one player on each side.") Sides sides,
		List<ResultInput.Scorer> scorers, List<SetScore> sets, List<String> absent) {

	public record Sides(List<String> home, List<String> away) {
	}

	public ResultInput toInput() {
		return new ResultInput(homeScore, awayScore, sides.home(), sides.away(), scorers, sets, absent);
	}

}
