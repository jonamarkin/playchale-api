package com.playchale.api.competitions.internal.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fixture pairings by the circle method: everyone plays everyone once, nobody twice in a round. An
 * odd number of teams gives one team a rest each round. Same as the web app's roundRobin.
 */
public final class RoundRobin {

	public record Pairing(UUID home, UUID away) {
	}

	private RoundRobin() {
	}

	public static List<List<Pairing>> rounds(List<UUID> teams) {
		if (teams.size() < 2) {
			return List.of();
		}
		var order = new ArrayList<UUID>(teams);
		if (order.size() % 2 == 1) {
			order.add(null); // a bye
		}
		int n = order.size();
		var rounds = new ArrayList<List<Pairing>>();
		for (int round = 0; round < n - 1; round++) {
			var pairs = new ArrayList<Pairing>();
			for (int i = 0; i < n / 2; i++) {
				var home = order.get(i);
				var away = order.get(n - 1 - i);
				if (home == null || away == null) {
					continue;
				}
				// Alternate who's at home, so it's not always the same side of the list.
				pairs.add(round % 2 == 1 ? new Pairing(away, home) : new Pairing(home, away));
			}
			rounds.add(pairs);
			// Keep the first team where it is and turn the rest one step.
			order.add(1, order.removeLast());
		}
		return rounds;
	}

}
