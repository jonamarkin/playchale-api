package com.playchale.api.competitions.internal.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class RoundRobinTest {

	@ParameterizedTest
	@ValueSource(ints = { 3, 4, 5, 8 })
	void everyonePlaysEveryoneOnceAndNobodyTwiceInARound(int size) {
		var teams = IntStream.range(0, size).mapToObj(i -> UUID.randomUUID()).toList();
		var rounds = RoundRobin.rounds(teams);

		assertThat(rounds).hasSize(size % 2 == 0 ? size - 1 : size);
		var pairs = new HashSet<Set<UUID>>();
		for (var round : rounds) {
			var inRound = round.stream().flatMap(p -> List.of(p.home(), p.away()).stream()).toList();
			assertThat(inRound).doesNotHaveDuplicates();
			round.forEach(p -> assertThat(pairs.add(Set.of(p.home(), p.away()))).as("played twice").isTrue());
		}
		assertThat(pairs).hasSize(size * (size - 1) / 2);
	}

}
