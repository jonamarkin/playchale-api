package com.playchale.api.events.internal.domain;

import java.time.Instant;

import com.playchale.api.shared.error.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A game is always set up in a way its draw and its results can handle, whatever the admin sends. */
class GameSettingsTest {

	private static GameSettings.Asked asked(String discipline) {
		return new GameSettings.Asked(discipline, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
	}

	@Test
	void aReadyMadeGameStartsWithItsOwnSettings() {
		var tableTennis = GameSettings.of(asked("table-tennis"));
		assertThat(tableTennis.name()).isEqualTo("Table tennis");
		assertThat(tableTennis.entryKind()).isEqualTo("single");
		assertThat(tableTennis.format()).isEqualTo("knockout");
		assertThat(tableTennis.scoring()).isEqualTo("sets");
		assertThat(tableTennis.bestOf()).isEqualTo(3);

		var ludo = GameSettings.of(asked("ludo"));
		assertThat(ludo.format()).isEqualTo("placings");
		assertThat(ludo.scoring()).isEqualTo("placings");
		assertThat(ludo.heatSize()).isEqualTo(4);
		assertThat(ludo.advancePerHeat()).isEqualTo(1);

		var oware = GameSettings.of(asked("oware"));
		assertThat(oware.scoring()).isEqualTo("outcome");
		// A knockout can't end level, whatever oware's habit in a league.
		assertThat(oware.drawsAllowed()).isFalse();
	}

	@Test
	void aLeagueTakesTheDisciplinesDrawsAndAKnockoutNever() {
		var league = GameSettings.of(new GameSettings.Asked("chess", null, null, null, null, "league", null, null, null, null, null, null,
				null, null, null, null));
		assertThat(league.drawsAllowed()).isTrue();
		var knockout = GameSettings.of(new GameSettings.Asked("football", null, "Men", null, 7, "knockout", null, null, true, true, null,
				null, "Pitch 1", Instant.parse("2030-06-01T09:00:00Z"), null, null));
		assertThat(knockout.drawsAllowed()).isFalse();
		assertThat(knockout.thirdPlace()).isTrue();
		assertThat(knockout.category()).isEqualTo("Men");
		assertThat(knockout.teamSize()).isEqualTo(7);
		assertThat(knockout.location()).isEqualTo("Pitch 1");
	}

	@Test
	void whatAGameCantBeIsRefused() {
		// Football is between teams; ludo is for placings; volleyball sets come in odd numbers.
		assertThatThrownBy(() -> GameSettings.of(new GameSettings.Asked("football", null, null, "single", null, null, null, null, null, null,
				null, null, null, null, null, null))).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> GameSettings.of(new GameSettings.Asked("ludo", null, null, null, null, "knockout", null, null, null, null,
				null, null, null, null, null, null))).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> GameSettings.of(new GameSettings.Asked("volleyball", null, null, null, null, null, null, 2, null, null,
				null, null, null, null, null, null))).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> GameSettings.of(new GameSettings.Asked("race", null, null, null, null, null, null, null, null, null, 6, 6,
				null, null, null, null))).hasMessageContaining("go through");
		assertThatThrownBy(() -> GameSettings.of(asked("hurling"))).isInstanceOf(BusinessException.class);
	}

	@Test
	void aCustomGameNeedsANameAndTakesTheScoringItsGiven() {
		assertThatThrownBy(() -> GameSettings.of(asked("custom"))).hasMessageContaining("name");
		var game = GameSettings.of(new GameSettings.Asked("custom", "  Musical   chairs ", null, "single", null, "knockout", "outcome", null,
				null, null, null, null, null, null, null, null));
		assertThat(game.name()).isEqualTo("Musical chairs");
		assertThat(game.scoring()).isEqualTo("outcome");
		var placings = GameSettings.of(new GameSettings.Asked("custom", "Sack race", null, "single", null, "placings", "sets", null, null,
				null, 6, 2, null, null, null, null));
		// Placings are always scored by finishing order.
		assertThat(placings.scoring()).isEqualTo("placings");
		assertThat(placings.bestOf()).isNull();
	}

	@Test
	void poolsThenAKnockoutDefaultToPoolsOfFourWithTwoThroughAndCanEndLevelInAPool() {
		var pools = GameSettings.of(new GameSettings.Asked("football", null, null, null, null, "pools", null, null, null, true, null, null,
				null, null, null, null));
		assertThat(pools.poolSize()).isEqualTo(4);
		assertThat(pools.advancePerPool()).isEqualTo(2);
		assertThat(pools.drawsAllowed()).isTrue();
		assertThat(pools.thirdPlace()).isTrue();
		assertThatThrownBy(() -> GameSettings.of(new GameSettings.Asked("football", null, null, null, null, "pools", null, null, null, null,
				null, null, null, null, 3, 3))).hasMessageContaining("go through");
		assertThatThrownBy(() -> GameSettings.of(new GameSettings.Asked("ludo", null, null, null, null, "pools", null, null, null, null,
				null, null, null, null, null, null))).isInstanceOf(BusinessException.class);
	}

	@Test
	void aPairIsTwo() {
		var pairs = GameSettings.of(new GameSettings.Asked("badminton", null, null, "pair", 5, null, null, null, null, null, null, null, null,
				null, null, null));
		assertThat(pairs.teamSize()).isEqualTo(2);
	}

}
