package com.playchale.api.events.internal.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Dealing pools, who comes through them, and a knockout that keeps a pool's own apart at first. */
class PoolsTest {

	private static final List<UUID> E = IntStream.range(0, 16).mapToObj(i -> UUID.nameUUIDFromBytes(("entry" + i).getBytes())).toList();

	@Test
	void tenInPoolsOfFourAreThreePoolsOfFourThreeAndThree() {
		var pools = Pools.deal(E.subList(0, 10), 4);
		assertThat(pools).extracting(List::size).containsExactly(4, 3, 3);
		assertThat(Pools.deal(E.subList(0, 3), 4)).hasSize(1);
	}

	@Test
	void winnersComeThroughFirstThenRunnersUp() {
		var a = List.of(E.get(0), E.get(1), E.get(2));
		var b = List.of(E.get(3), E.get(4));
		assertThat(Pools.through(List.of(a, b), 2)).containsExactly(E.get(0), E.get(3), E.get(1), E.get(4));
		// A pool smaller than the number going through sends everyone it has.
		assertThat(Pools.through(List.of(a, List.of(E.get(5))), 2)).containsExactly(E.get(0), E.get(5), E.get(1));
	}

	@Test
	void aWinnerMeetsAnotherPoolsRunnerUp() {
		// Two pools, two through: A1 v B2 and B1 v A2.
		var poolOf = Map.of(E.get(0), 1, E.get(1), 1, E.get(2), 2, E.get(3), 2);
		var draw = Pools.bracket(List.of(E.get(0), E.get(2), E.get(1), E.get(3)), poolOf);
		assertThat(draw.ties()).allSatisfy(t -> assertThat(poolOf.get(t.home())).isNotEqualTo(poolOf.get(t.away())));
	}

	@Test
	void threePoolsOfTwoThroughNeverOpenWithAPoolsOwnPair() {
		// Six through into a bracket of eight: seeding alone would pair the third pool's winner with its runner-up.
		var poolOf = new HashMap<UUID, Integer>();
		for (int pool = 0; pool < 3; pool++) {
			poolOf.put(E.get(pool * 2), pool + 1);
			poolOf.put(E.get(pool * 2 + 1), pool + 1);
		}
		var through = Pools.through(List.of(List.of(E.get(0), E.get(1)), List.of(E.get(2), E.get(3)), List.of(E.get(4), E.get(5))), 2);
		var draw = Pools.bracket(through, poolOf);
		assertThat(draw.ties()).hasSize(2);
		assertThat(draw.byes()).hasSize(2);
		assertThat(draw.ties()).allSatisfy(t -> assertThat(poolOf.get(t.home())).isNotEqualTo(poolOf.get(t.away())));
	}

}
