package com.playchale.api.shared.draws;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SortedMap;
import java.util.UUID;

/**
 * A straight knockout: lose once and you're out. The bracket is the next power of two at or above
 * the number of teams, and teams that have nobody to play in the first round get a bye. Ties are
 * numbered from the top of the bracket, and the winners of ties 0 and 1 meet in the next round's
 * tie 0, so the bracket holds its shape however the results fall.
 *
 * <p>The draw is seeded the way cups are: the first team to enter meets the last, and the first two
 * can only meet in the final. Byes go to the teams at the top of the list, on opposite sides.
 */
public final class Knockout {

	/** One tie to play: who's in it, and where it sits in its round. */
	public record Tie(int round, int slot, UUID home, UUID away) {
	}

	/**
	 * A first round: the ties to play, and the teams whose bye puts them straight into the next
	 * round, each at the slot they'd have won.
	 */
	public record Draw(List<Tie> ties, SortedMap<Integer, UUID> byes) {
	}

	private Knockout() {
	}

	/** The bracket's width: the first power of two that fits everyone. */
	public static int size(int teams) {
		int size = 1;
		while (size < teams) {
			size *= 2;
		}
		return size;
	}

	/** How many rounds it takes to get from this many teams to one winner. */
	public static int rounds(int teams) {
		return Integer.numberOfTrailingZeros(size(teams));
	}

	/**
	 * What a round is called, by how many ties are left in it: the last is the final, the one before
	 * it the semi-finals, and further back they're named for how many teams are still in.
	 */
	public static String name(int ties) {
		return switch (ties) {
			case 1 -> "Final";
			case 2 -> "Semi-finals";
			case 4 -> "Quarter-finals";
			default -> "Round of %d".formatted(ties * 2);
		};
	}

	/**
	 * The order the seeds sit in the bracket, top to bottom: 1, 8, 4, 5, 2, 7, 3, 6 for eight. Built
	 * by folding the bracket in half again and again, which is what keeps the top seeds apart.
	 */
	static List<Integer> seedOrder(int size) {
		var order = new ArrayList<>(List.of(1));
		while (order.size() < size) {
			var next = new ArrayList<Integer>(order.size() * 2);
			var pairsTo = order.size() * 2 + 1;
			for (var seed : order) {
				next.add(seed);
				next.add(pairsTo - seed);
			}
			order = next;
		}
		return order;
	}

	/** The first round, from the teams in the order they entered (the order is the seeding). */
	public static Draw firstRound(List<UUID> teams) {
		var order = seedOrder(size(teams.size()));
		var ties = new ArrayList<Tie>();
		var byes = new java.util.TreeMap<Integer, UUID>();
		for (int slot = 0; slot < order.size() / 2; slot++) {
			// Seeds beyond the teams we have are nobody: whoever drew them goes through unopposed.
			var home = team(teams, order.get(slot * 2));
			var away = team(teams, order.get(slot * 2 + 1));
			if (home == null || away == null) {
				byes.put(slot, home == null ? away : home);
			}
			else {
				ties.add(new Tie(1, slot, home, away));
			}
		}
		return new Draw(List.copyOf(ties), java.util.Collections.unmodifiableSortedMap(byes));
	}

	private static UUID team(List<UUID> teams, int seed) {
		return seed <= teams.size() ? teams.get(seed - 1) : null;
	}

	/**
	 * The round after this one: whoever came through, paired in slot order. Two teams meet when their
	 * slots sit next to each other in the bracket.
	 *
	 * @param through who goes through, by the slot of the tie they came from (a bye's slot is its own)
	 */
	public static List<Tie> nextRound(int round, SortedMap<Integer, UUID> through) {
		var ties = new ArrayList<Tie>();
		var slots = new ArrayList<>(through.keySet());
		for (int i = 0; i + 1 < slots.size(); i += 2) {
			ties.add(new Tie(round, i / 2, through.get(slots.get(i)), through.get(slots.get(i + 1))));
		}
		return List.copyOf(ties);
	}

	/** The bracket as it stands, for showing: how many ties each round has, in order. */
	public static List<Integer> shape(int teams) {
		var shape = new LinkedHashMap<Integer, Integer>();
		for (int ties = size(teams) / 2, round = 1; ties >= 1; ties /= 2, round++) {
			shape.put(round, ties);
		}
		return List.copyOf(shape.values());
	}

}
