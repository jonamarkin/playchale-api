package com.playchale.api.events.internal.domain;

import java.util.List;

import com.playchale.api.shared.error.BusinessException;

/**
 * What makes a match result whole, for each way a game is scored. A coordinator types what happened;
 * this works out who won, or refuses a result the draw couldn't carry on from: a knockout match
 * nobody won, sets that don't add up to a winner, a draw where the game doesn't allow one.
 */
public final class ResultRules {

	public enum Side {
		HOME, AWAY
	}

	public record SetScore(int home, int away) {
	}

	/**
	 * What the coordinator sent.
	 *
	 * @param winner    "home", "away" or "draw", for a game scored by who won, or a walkover
	 * @param decidedBy "score" (the default), "penalties" (a level knockout match), or "walkover"
	 */
	public record Asked(Integer homeScore, Integer awayScore, List<SetScore> sets, String winner, String decidedBy, Integer homePenalties,
			Integer awayPenalties) {
	}

	/** The result as kept: the score (sets won, for sets), and who won, null for a draw. */
	public record Settled(Integer homeScore, Integer awayScore, List<SetScore> sets, Side winner, String decidedBy, Integer homePenalties,
			Integer awayPenalties) {
	}

	private ResultRules() {
	}

	/**
	 * @param knockout whether someone has to go through
	 * @param draws    whether this league lets a match end level
	 */
	public static Settled settle(String scoring, Integer bestOf, boolean knockout, boolean draws, Asked asked) {
		if ("walkover".equals(asked.decidedBy())) {
			var winner = side(asked.winner());
			if (winner == null) {
				throw BusinessException.invalid("Say who turned up.");
			}
			return new Settled(null, null, List.of(), winner, "walkover", null, null);
		}
		return switch (scoring) {
			case "score" -> score(knockout, draws, asked);
			case "sets" -> sets(bestOf == null ? 3 : bestOf, asked);
			case "outcome" -> outcome(knockout, draws, asked);
			default -> throw BusinessException.invalid("This game is played for places, not matches.");
		};
	}

	private static Settled score(boolean knockout, boolean draws, Asked asked) {
		var home = asked.homeScore();
		var away = asked.awayScore();
		if (home == null || away == null || home < 0 || away < 0 || home > 999 || away > 999) {
			throw BusinessException.invalid("Put in both scores.");
		}
		if (!home.equals(away)) {
			return new Settled(home, away, List.of(), home > away ? Side.HOME : Side.AWAY, "score", null, null);
		}
		if (knockout) {
			var hp = asked.homePenalties();
			var ap = asked.awayPenalties();
			if (!"penalties".equals(asked.decidedBy()) || hp == null || ap == null || hp < 0 || ap < 0 || hp.equals(ap)) {
				throw BusinessException.invalid("Someone has to go through. Put in the penalties, or how it was settled.");
			}
			return new Settled(home, away, List.of(), hp > ap ? Side.HOME : Side.AWAY, "penalties", hp, ap);
		}
		if (!draws) {
			throw BusinessException.invalid("This game can’t end level. Check the score.");
		}
		return new Settled(home, away, List.of(), null, "score", null, null);
	}

	private static Settled sets(int bestOf, Asked asked) {
		var sets = asked.sets() == null ? List.<SetScore>of() : asked.sets();
		var needed = bestOf / 2 + 1;
		if (sets.isEmpty() || sets.size() > bestOf) {
			throw BusinessException.invalid("Put in each set: best of %d.".formatted(bestOf));
		}
		int home = 0;
		int away = 0;
		for (int i = 0; i < sets.size(); i++) {
			var set = sets.get(i);
			if (set.home() < 0 || set.away() < 0 || set.home() > 99 || set.away() > 99 || set.home() == set.away()) {
				throw BusinessException.invalid("Set %d needs a winner.".formatted(i + 1));
			}
			if (home == needed || away == needed) {
				throw BusinessException.invalid("The match was over after set %d.".formatted(i));
			}
			if (set.home() > set.away()) {
				home++;
			}
			else {
				away++;
			}
		}
		if (home != needed && away != needed) {
			throw BusinessException.invalid("Nobody has won %d sets yet.".formatted(needed));
		}
		return new Settled(home, away, List.copyOf(sets), home > away ? Side.HOME : Side.AWAY, "score", null, null);
	}

	private static Settled outcome(boolean knockout, boolean draws, Asked asked) {
		if ("draw".equals(asked.winner())) {
			if (knockout || !draws) {
				throw BusinessException.invalid("Someone has to win this one.");
			}
			return new Settled(null, null, List.of(), null, "score", null, null);
		}
		var winner = side(asked.winner());
		if (winner == null) {
			throw BusinessException.invalid("Say who won.");
		}
		return new Settled(null, null, List.of(), winner, "score", null, null);
	}

	private static Side side(String winner) {
		return "home".equals(winner) ? Side.HOME : "away".equals(winner) ? Side.AWAY : null;
	}

}
