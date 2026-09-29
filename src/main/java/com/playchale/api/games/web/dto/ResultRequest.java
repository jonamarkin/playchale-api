package com.playchale.api.games.web.dto;

import java.util.List;

import com.playchale.api.games.internal.domain.ResultInput;
import com.playchale.api.games.internal.domain.SetScore;
import jakarta.validation.constraints.NotNull;

/**
 * The web app's ResultInput. Players are named by the roster's keys: a player's ID, or
 * "guest:&lt;token&gt;". The penalties are only for a knockout tie that ended level.
 */
public record ResultRequest(int homeScore, int awayScore, @NotNull(message = "Put at least one player on each side.") Sides sides,
		List<ResultInput.Scorer> scorers, List<SetScore> sets, List<String> absent, Integer homePenalties, Integer awayPenalties) {

	public record Sides(List<String> home, List<String> away) {
	}

	public ResultInput toInput() {
		return new ResultInput(homeScore, awayScore, sides.home(), sides.away(), scorers, sets, absent, homePenalties, awayPenalties);
	}

}
