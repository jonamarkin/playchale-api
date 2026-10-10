package com.playchale.api.events.internal.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.playchale.api.shared.draws.Knockout;

/**
 * Pools, then a knockout: entries dealt into pools that each play everyone once, then the best of
 * each pool into a knockout where a pool's winner meets another pool's runner-up, and nobody meets
 * someone from their own pool in the first round if it can be helped. Same as the web app's pools.
 */
public final class Pools {

	private Pools() {
	}

	/** Dealt like cards into as few pools of {@code size} as fit everyone, so no two pools differ by more than one. */
	public static List<List<UUID>> deal(List<UUID> entries, int size) {
		return Standings.heats(entries, size);
	}

	/**
	 * Who goes through, best first: every pool's winner (in pool order), then every runner-up, and on.
	 * A pool with fewer in it than go through sends everyone.
	 *
	 * @param tables each pool's table, best first
	 */
	public static List<UUID> through(List<List<UUID>> tables, int advance) {
		var through = new ArrayList<UUID>();
		for (int rank = 0; rank < advance; rank++) {
			for (var table : tables) {
				if (rank < table.size()) {
					through.add(table.get(rank));
				}
			}
		}
		return through;
	}

	/**
	 * The knockout's first round, seeded from who came through (best first). Where a tie would pair
	 * two from the same pool, its second side swaps with another tie's, so they can only meet later.
	 */
	public static Knockout.Draw bracket(List<UUID> through, Map<UUID, Integer> poolOf) {
		var draw = Knockout.firstRound(through);
		var ties = new ArrayList<>(draw.ties());
		for (int i = 0; i < ties.size(); i++) {
			var tie = ties.get(i);
			if (!samePool(tie.home(), tie.away(), poolOf)) {
				continue;
			}
			for (int j = 0; j < ties.size(); j++) {
				var other = ties.get(j);
				if (j == i || samePool(tie.home(), other.away(), poolOf) || samePool(other.home(), tie.away(), poolOf)) {
					continue;
				}
				ties.set(i, new Knockout.Tie(tie.round(), tie.slot(), tie.home(), other.away()));
				ties.set(j, new Knockout.Tie(other.round(), other.slot(), other.home(), tie.away()));
				break;
			}
		}
		return new Knockout.Draw(List.copyOf(ties), draw.byes());
	}

	private static boolean samePool(UUID a, UUID b, Map<UUID, Integer> poolOf) {
		return a != null && b != null && Objects.equals(poolOf.get(a), poolOf.get(b));
	}

}
