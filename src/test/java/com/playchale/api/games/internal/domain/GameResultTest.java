package com.playchale.api.games.internal.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.playchale.api.games.internal.domain.ResultInput.Scorer;
import com.playchale.api.market.Market;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rules for a result. A plain unit test: no Spring, no database. */
class GameResultTest {

	private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");

	private static final Instant KICKOFF = NOW.plus(Duration.ofHours(2));

	private final UUID host = UUID.randomUUID();

	private final UUID kojo = UUID.randomUUID();

	private final UUID ama = UUID.randomUUID();

	private final UUID yaw = UUID.randomUUID();

	private Game game(String sport, String format) {
		var game = new Game(new GameDetails(sport, format, "Test", KICKOFF, 60, "unlisted", null, null, "Legon", null, 10, 0, "public", null),
				host, Market.get("GH"), NOW);
		game.playAt("Legon", null);
		game.join(kojo, NOW);
		game.join(ama, NOW);
		game.join(yaw, NOW);
		return game;
	}

	private static List<String> keys(UUID... ids) {
		return List.of(ids).stream().map(UUID::toString).toList();
	}

	@Test
	void aFootballResultKeepsGoalsAndAssistsForPlayersOnASide() {
		var game = game("football", "5-a-side");
		var input = new ResultInput(3, 1, keys(host, kojo), keys(ama), List.of(new Scorer(kojo.toString(), 2, 1, 40),
				new Scorer(ama.toString(), 1, null, null), new Scorer(UUID.randomUUID().toString(), 5, null, null)), null, keys(yaw));
		var result = new GameResult(game, input, host, KICKOFF);

		assertThat(result.lineOf(kojo)).get().satisfies(l -> {
			assertThat(l.goals()).isEqualTo(2);
			assertThat(l.assists()).isEqualTo(1);
			assertThat(l.points()).as("football doesn't keep points").isZero();
		});
		assertThat(result.outcomeFor(kojo)).contains("W");
		assertThat(result.outcomeFor(ama)).contains("L");
		assertThat(result.outcomeFor(yaw)).as("absent").isEmpty();
		assertThat(result.lineOf(yaw)).get().extracting(ResultLine::side).isEqualTo("absent");
	}

	@Test
	void theResultMustAddUp() {
		var game = game("football", "5-a-side");
		assertThatThrownBy(() -> new GameResult(game, new ResultInput(1, 0, keys(host), List.of(), null, null, null), host, KICKOFF))
			.hasMessage("Put at least one player on each side.");
		assertThatThrownBy(() -> new GameResult(game, new ResultInput(1, 0, keys(host, kojo), keys(kojo), null, null, null), host, KICKOFF))
			.hasMessage("Each player can only be on one side.");
		assertThatThrownBy(() -> new GameResult(game, new ResultInput(1, 0, keys(host), keys(UUID.randomUUID()), null, null, null), host, KICKOFF))
			.hasMessage("Each player can only be on one side.");
		assertThatThrownBy(() -> new GameResult(game, new ResultInput(1, 0, keys(host, kojo), keys(ama),
				List.of(new Scorer(kojo.toString(), 2, null, null)), null, null), host, KICKOFF))
			.hasMessage("The goals add up to more than the score.");
	}

	@Test
	void setBasedSportsAreScoredFromTheirSets() {
		var game = game("volleyball", "6v6");
		var sets = List.of(new SetScore(25, 20), new SetScore(18, 25), new SetScore(15, 11));
		var result = new GameResult(game, new ResultInput(0, 0, keys(host, kojo), keys(ama, yaw), null, sets, null), host, KICKOFF);
		assertThat(result.getHomeScore()).isEqualTo(2);
		assertThat(result.getAwayScore()).isEqualTo(1);
		assertThat(result.getSets()).hasSize(3);

		assertThatThrownBy(() -> new GameResult(game, new ResultInput(0, 0, keys(host), keys(ama), null, List.of(), null), host, KICKOFF))
			.hasMessage("Add the score of at least one set.");
		assertThatThrownBy(() -> new GameResult(game, new ResultInput(0, 0, keys(host), keys(ama), null,
				List.of(new SetScore(25, 20), new SetScore(20, 20)), null), host, KICKOFF))
			.hasMessage("Set 2 can’t end level. Check its score.");
	}

	@Test
	void onlyPlayersWhoWereThereCheckAndTheHostCorrectsInstead() {
		var game = game("football", "5-a-side");
		var result = new GameResult(game, new ResultInput(2, 2, keys(host, kojo), keys(ama), null, null, keys(yaw)), host, KICKOFF);

		assertThatThrownBy(() -> result.confirm(host, KICKOFF)).hasMessage("You recorded this result.");
		assertThatThrownBy(() -> result.dispute(host, null, KICKOFF)).hasMessage("You recorded this result. Correct it instead.");
		assertThatThrownBy(() -> result.confirm(yaw, KICKOFF)).hasMessage("Only players who were in this game can check the result.");

		result.confirm(kojo, KICKOFF);
		assertThat(result.dispute(kojo, "  It was 3-2  ", KICKOFF)).isEqualTo("It was 3-2");
		assertThat(result.getConfirmations()).as("disputing takes back a confirmation").isEmpty();
		assertThat(result.getDisputes()).singleElement().extracting(Dispute::reason).isEqualTo("It was 3-2");

		result.record(game, new ResultInput(3, 2, keys(host, kojo), keys(ama), null, null, keys(yaw)), host, KICKOFF);
		assertThat(result.getDisputes()).as("a correction starts the checking again").isEmpty();
	}

	@Test
	void aResultNeedsAGameThatsKickedOffAndIsStillOn() {
		var game = game("football", "5-a-side");
		assertThatThrownBy(() -> game.complete(NOW)).hasMessage("You can record the result once the game has started.");
		game.complete(KICKOFF);
		assertThat(game.getStatus()).isEqualTo(Game.COMPLETED);
	}

}
