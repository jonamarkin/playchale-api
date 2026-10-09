package com.playchale.api.events.internal.domain;

import java.util.List;

import com.playchale.api.events.internal.domain.ResultRules.Asked;
import com.playchale.api.events.internal.domain.ResultRules.SetScore;
import com.playchale.api.events.internal.domain.ResultRules.Side;
import com.playchale.api.shared.error.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A result is only kept when the draw can carry on from it. */
class ResultRulesTest {

	private static Asked score(int home, int away) {
		return new Asked(home, away, null, null, null, null, null);
	}

	private static Asked sets(int... points) {
		var sets = new java.util.ArrayList<SetScore>();
		for (int i = 0; i < points.length; i += 2) {
			sets.add(new SetScore(points[i], points[i + 1]));
		}
		return new Asked(null, null, sets, null, null, null, null);
	}

	@Test
	void aScoreDecidesItAndALevelKnockoutNeedsPenalties() {
		assertThat(ResultRules.settle("score", null, true, false, score(2, 1)).winner()).isEqualTo(Side.HOME);
		assertThatThrownBy(() -> ResultRules.settle("score", null, true, false, score(1, 1))).hasMessageContaining("penalties");
		var shootout = ResultRules.settle("score", null, true, false, new Asked(1, 1, null, null, "penalties", 3, 4));
		assertThat(shootout.winner()).isEqualTo(Side.AWAY);
		assertThat(shootout.decidedBy()).isEqualTo("penalties");
		assertThatThrownBy(() -> ResultRules.settle("score", null, true, false, new Asked(1, 1, null, null, "penalties", 4, 4)))
			.isInstanceOf(BusinessException.class);
	}

	@Test
	void aLeagueDrawsOnlyWhereTheGameAllowsIt() {
		assertThat(ResultRules.settle("score", null, false, true, score(0, 0)).winner()).isNull();
		assertThatThrownBy(() -> ResultRules.settle("score", null, false, false, score(70, 70))).hasMessageContaining("level");
		assertThat(ResultRules.settle("outcome", null, false, true, new Asked(null, null, null, "draw", null, null, null)).winner()).isNull();
		assertThatThrownBy(() -> ResultRules.settle("outcome", null, true, true, new Asked(null, null, null, "draw", null, null, null)))
			.hasMessageContaining("win");
		assertThat(ResultRules.settle("outcome", null, true, false, new Asked(null, null, null, "away", null, null, null)).winner())
			.isEqualTo(Side.AWAY);
	}

	@Test
	void setsAddUpToAWinnerAndStopThere() {
		var three = ResultRules.settle("sets", 3, true, false, sets(11, 7, 9, 11, 11, 5));
		assertThat(three.winner()).isEqualTo(Side.HOME);
		assertThat(three.homeScore()).isEqualTo(2);
		assertThat(three.awayScore()).isEqualTo(1);
		assertThat(ResultRules.settle("sets", 5, true, false, sets(5, 11, 6, 11, 8, 11)).winner()).isEqualTo(Side.AWAY);
		// Over after two, so a third set is a mistake; and one set isn't two.
		assertThatThrownBy(() -> ResultRules.settle("sets", 3, true, false, sets(11, 3, 11, 4, 11, 9))).hasMessageContaining("over");
		assertThatThrownBy(() -> ResultRules.settle("sets", 3, true, false, sets(11, 3))).hasMessageContaining("2 sets");
		assertThatThrownBy(() -> ResultRules.settle("sets", 3, true, false, sets(11, 11, 11, 3))).hasMessageContaining("Set 1");
	}

	@Test
	void aWalkoverNeedsOnlyWhoTurnedUp() {
		var walkover = ResultRules.settle("sets", 3, true, false, new Asked(null, null, null, "away", "walkover", null, null));
		assertThat(walkover.winner()).isEqualTo(Side.AWAY);
		assertThat(walkover.decidedBy()).isEqualTo("walkover");
		assertThatThrownBy(() -> ResultRules.settle("score", null, true, false, new Asked(null, null, null, null, "walkover", null, null)))
			.isInstanceOf(BusinessException.class);
	}

}
