package com.playchale.api.events.internal.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.playchale.api.shared.draws.Knockout;

/**
 * When and where each match or heat of a game is played, from the coordinator's plan: a start, how
 * long each one takes, and the places (courts, tables, boards) used at once.
 *
 * <p>A game is played in waves: a knockout's rounds, a league's rounds, a placings game's heats and
 * then its final. A wave fills the places side by side and takes as many turns as it needs; the
 * next wave starts when it's done. So a knockout's later rounds have their time before anyone knows
 * who's in them, and a match made later (the next round, a final) lands where the plan said.
 * Same as the web app's {@code timetableSlot}.
 */
public final class Timetable {

	public record Plan(Instant start, int minutes, List<String> places) {

		public Plan {
			if (places.isEmpty() || minutes < 1) {
				throw new IllegalArgumentException("a plan needs a place and a length");
			}
			places = List.copyOf(places);
		}

	}

	public record Slot(Instant startsAt, String location) {
	}

	private Timetable() {
	}

	/** The {@code index}th thing (from 0) in wave {@code wave} (from 0), given how many are in each wave. */
	public static Slot slot(Plan plan, List<Integer> waves, int wave, int index) {
		var places = plan.places().size();
		var turns = 0;
		for (int w = 0; w < wave; w++) {
			turns += Math.ceilDiv(waves.get(w), places);
		}
		turns += index / places;
		return new Slot(plan.start().plus(Duration.ofMinutes((long) turns * plan.minutes())), plan.places().get(index % places));
	}

	/**
	 * A knockout's waves: the first round's real ties (a bye isn't played), then each round after it,
	 * with the match for third place in the last wave, before the final.
	 */
	public static List<Integer> knockoutWaves(int firstRoundSlots, int firstRoundTies, boolean thirdPlace) {
		var rounds = Knockout.rounds(firstRoundSlots * 2);
		var waves = new ArrayList<Integer>();
		waves.add(firstRoundTies);
		for (int round = 2; round <= rounds; round++) {
			var ties = firstRoundSlots >> (round - 1);
			waves.add(round == rounds && thirdPlace && rounds >= 2 ? ties + 1 : ties);
		}
		return waves;
	}

	/** Where a knockout match sits in its wave: a first-round tie by its order among the real ones, the third-place match first in the last. */
	public static int knockoutIndex(int round, int slot, boolean thirdPlace, int rounds, boolean withThird, int orderInFirstRound) {
		if (round == 1) {
			return orderInFirstRound;
		}
		if (round == rounds && withThird) {
			return thirdPlace ? 0 : slot + 1;
		}
		return slot;
	}

	/** A placings game's waves: its heats, then the final. A single heat is the final itself. */
	public static List<Integer> heatWaves(int heats) {
		return heats == 0 ? List.of(1) : List.of(heats, 1);
	}

}
