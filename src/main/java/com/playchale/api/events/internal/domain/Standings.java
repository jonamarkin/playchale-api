package com.playchale.api.events.internal.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.playchale.api.shared.error.BusinessException;

/**
 * How a game ends up, and how the whole event adds up: heats into a final, a league table, and the
 * overall table of groups. Pure, so the same sums give the same answer wherever they're done; the
 * web app's mock does them the same way (utils/events.ts).
 */
public final class Standings {

	private Standings() {
	}

	/* ------------------------------------------------------------------ heats */

	/**
	 * Entries (in draw order) into heats of at most {@code size}, as even as possible and dealt out
	 * like cards, so the first few in the order don't all meet in heat 1: 13 in heats of 6 are 5, 4
	 * and 4. Everyone in one heat when they fit, and that heat is the final.
	 */
	public static List<List<UUID>> heats(List<UUID> entries, int size) {
		if (entries.size() <= size) {
			return List.of(List.copyOf(entries));
		}
		var count = (entries.size() + size - 1) / size;
		var heats = new ArrayList<List<UUID>>();
		for (int i = 0; i < count; i++) {
			heats.add(new ArrayList<>());
		}
		for (int i = 0; i < entries.size(); i++) {
			heats.get(i % count).add(entries.get(i));
		}
		return heats.stream().map(List::copyOf).toList();
	}

	/** A finishing place in a heat. */
	public record Placed(UUID entry, int place) {
	}

	/** Who goes through to the final: the best {@code advance} of each heat, heat by heat. */
	public static List<UUID> through(List<List<Placed>> heats, int advance) {
		var through = new ArrayList<UUID>();
		for (var heat : heats) {
			heat.stream().sorted(Comparator.comparingInt(Placed::place)).limit(advance).forEach(p -> through.add(p.entry()));
		}
		return through;
	}

	/** Every entry in the heat placed once, 1st to last, with no place twice and none skipped. */
	public static void checkPlaces(List<UUID> inHeat, Map<UUID, Integer> places) {
		if (!new HashSet<>(inHeat).equals(places.keySet())) {
			throw BusinessException.invalid("Give everyone in the heat a place.");
		}
		var seen = new HashSet<Integer>();
		for (var place : places.values()) {
			if (place == null || place < 1 || place > inHeat.size() || !seen.add(place)) {
				throw BusinessException.invalid("Each place from 1st to %s once.".formatted(ordinal(inHeat.size())));
			}
		}
	}

	/* ------------------------------------------------------------------ league */

	/** A match that's been played. {@code winner} is null for a draw; the scores are 0 where a game keeps none. */
	public record Played(UUID home, UUID away, int homeFor, int awayFor, UUID winner) {
	}

	public record Row(UUID entry, int played, int won, int drawn, int lost, int scored, int conceded, int points) {

		int difference() {
			return scored - conceded;
		}

	}

	/**
	 * The league table: 3 points a win, 1 a draw. Level on points, the better difference then the
	 * more scored; still level, the order they were drawn in.
	 */
	public static List<Row> table(List<UUID> entries, List<Played> played) {
		var rows = new LinkedHashMap<UUID, int[]>();
		entries.forEach(e -> rows.put(e, new int[7]));
		for (var match : played) {
			var home = rows.get(match.home());
			var away = rows.get(match.away());
			if (home == null || away == null) {
				continue;
			}
			home[0]++;
			away[0]++;
			home[4] += match.homeFor();
			home[5] += match.awayFor();
			away[4] += match.awayFor();
			away[5] += match.homeFor();
			if (match.winner() == null) {
				home[2]++;
				away[2]++;
				home[6] += 1;
				away[6] += 1;
			}
			else if (match.winner().equals(match.home())) {
				home[1]++;
				away[3]++;
				home[6] += 3;
			}
			else {
				away[1]++;
				home[3]++;
				away[6] += 3;
			}
		}
		var order = new HashMap<UUID, Integer>();
		for (int i = 0; i < entries.size(); i++) {
			order.put(entries.get(i), i);
		}
		return rows.entrySet().stream()
			.map(e -> new Row(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3], e.getValue()[4],
					e.getValue()[5], e.getValue()[6]))
			.sorted(Comparator.comparingInt(Row::points).reversed().thenComparing(Comparator.comparingInt(Row::difference).reversed())
				.thenComparing(Comparator.comparingInt(Row::scored).reversed()).thenComparing(r -> order.get(r.entry())))
			.toList();
	}

	/* ------------------------------------------------------------------ the event */

	public record GroupRow(UUID group, int points, int gold, int silver, int bronze) {
	}

	/**
	 * The overall table: each finished game's places, worth {@code points} to the group of whoever
	 * took them (1st, 2nd, 3rd...), with medals for the first three. Highest total first; level, the
	 * more golds, then silvers, then bronzes. Every group is in it, on nothing if need be.
	 *
	 * @param places each finished game's groups in finishing order (a place without a group: null)
	 */
	public static List<GroupRow> groups(List<UUID> groups, List<Integer> points, List<List<UUID>> places) {
		var rows = new LinkedHashMap<UUID, int[]>();
		groups.forEach(g -> rows.put(g, new int[4]));
		for (var game : places) {
			for (int i = 0; i < game.size(); i++) {
				var row = game.get(i) == null ? null : rows.get(game.get(i));
				if (row == null) {
					continue;
				}
				if (i < points.size()) {
					row[0] += points.get(i);
				}
				if (i < 3) {
					row[i + 1]++;
				}
			}
		}
		var order = new HashMap<UUID, Integer>();
		for (int i = 0; i < groups.size(); i++) {
			order.put(groups.get(i), i);
		}
		return rows.entrySet().stream().map(e -> new GroupRow(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3]))
			.sorted(Comparator.comparingInt(GroupRow::points).reversed().thenComparing(Comparator.comparingInt(GroupRow::gold).reversed())
				.thenComparing(Comparator.comparingInt(GroupRow::silver).reversed())
				.thenComparing(Comparator.comparingInt(GroupRow::bronze).reversed()).thenComparing(r -> order.get(r.group())))
			.toList();
	}

	/** Places to the groups that hold them, for {@link #groups}. */
	public static List<UUID> groupsOf(List<UUID> entriesInPlaceOrder, Map<UUID, UUID> groupOfEntry) {
		return entriesInPlaceOrder.stream().map(e -> e == null ? null : groupOfEntry.get(e)).toList();
	}

	public static String ordinal(int n) {
		var suffix = n % 100 >= 11 && n % 100 <= 13 ? "th" : switch (n % 10) {
			case 1 -> "st";
			case 2 -> "nd";
			case 3 -> "rd";
			default -> "th";
		};
		return n + suffix;
	}

}
