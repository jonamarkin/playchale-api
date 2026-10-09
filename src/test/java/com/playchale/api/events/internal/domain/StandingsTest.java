package com.playchale.api.events.internal.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import com.playchale.api.events.internal.domain.Standings.Placed;
import com.playchale.api.events.internal.domain.Standings.Played;
import com.playchale.api.shared.error.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Heats into a final, a league table, and the overall table of groups. */
class StandingsTest {

	private static final List<UUID> E = IntStream.range(0, 20).mapToObj(i -> UUID.nameUUIDFromBytes(("entry" + i).getBytes())).toList();

	private static final UUID JOY = UUID.nameUUIDFromBytes("joy".getBytes());

	private static final UUID HOPE = UUID.nameUUIDFromBytes("hope".getBytes());

	private static final UUID FAITH = UUID.nameUUIDFromBytes("faith".getBytes());

	@Test
	void runnersAreDealtIntoEvenHeatsAndTheBestGoThrough() {
		var heats = Standings.heats(E.subList(0, 13), 6);
		assertThat(heats).extracting(List::size).containsExactly(5, 4, 4);
		// Dealt like cards: the first three in the order are in three different heats.
		assertThat(heats.get(0)).contains(E.get(0)).doesNotContain(E.get(1), E.get(2));
		// Everyone fits in one: that heat is the final.
		assertThat(Standings.heats(E.subList(0, 4), 4)).hasSize(1);

		var placed = new ArrayList<List<Placed>>();
		for (var heat : heats) {
			var list = new ArrayList<Placed>();
			for (int i = 0; i < heat.size(); i++) {
				list.add(new Placed(heat.get(i), heat.size() - i));
			}
			placed.add(list);
		}
		var through = Standings.through(placed, 2);
		assertThat(through).hasSize(6).containsExactly(heats.get(0).get(4), heats.get(0).get(3), heats.get(1).get(3), heats.get(1).get(2),
				heats.get(2).get(3), heats.get(2).get(2));
	}

	@Test
	void everyoneInAHeatGetsOnePlace() {
		var heat = E.subList(0, 4);
		Standings.checkPlaces(heat, Map.of(E.get(0), 2, E.get(1), 1, E.get(2), 4, E.get(3), 3));
		assertThatThrownBy(() -> Standings.checkPlaces(heat, Map.of(E.get(0), 1, E.get(1), 1, E.get(2), 2, E.get(3), 3)))
			.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> Standings.checkPlaces(heat, Map.of(E.get(0), 1, E.get(1), 2, E.get(2), 3)))
			.hasMessageContaining("everyone");
		assertThatThrownBy(() -> Standings.checkPlaces(heat, Map.of(E.get(0), 1, E.get(1), 2, E.get(2), 3, E.get(3), 5)))
			.hasMessageContaining("4th");
	}

	@Test
	void aLeagueTableCountsWinsDrawsAndThenTheScore() {
		var a = E.get(0);
		var b = E.get(1);
		var c = E.get(2);
		var table = Standings.table(List.of(a, b, c), List.of(new Played(a, b, 1, 1, null), new Played(b, c, 3, 0, b), new Played(c, a, 2, 1, c)));
		assertThat(table).extracting(Standings.Row::entry).containsExactly(b, c, a);
		assertThat(table.get(0).points()).isEqualTo(4);
		assertThat(table.get(0).drawn()).isEqualTo(1);
		// c and a: c has 3 points, a has 1.
		assertThat(table.get(1).points()).isEqualTo(3);
		// Nobody has played: the order they were drawn in.
		assertThat(Standings.table(List.of(c, a, b), List.of())).extracting(Standings.Row::entry).containsExactly(c, a, b);
	}

	@Test
	void theOverallTableAddsUpPlacesAndBreaksTiesOnGolds() {
		// Joy: 1st and 3rd (5 + 1); Hope: 2nd and 2nd (3 + 3); Faith: 1st, and a 4th worth nothing.
		var table = Standings.groups(List.of(FAITH, HOPE, JOY), List.of(5, 3, 1), List.of(
				Arrays.asList(JOY, HOPE, null),
				Arrays.asList(FAITH, HOPE, JOY, FAITH)));
		assertThat(table).extracting(Standings.GroupRow::group).containsExactly(JOY, HOPE, FAITH);
		assertThat(table.get(0).points()).isEqualTo(6);
		assertThat(table.get(1).points()).isEqualTo(6);
		assertThat(table.get(0).gold()).isEqualTo(1);
		assertThat(table.get(1).silver()).isEqualTo(2);
		assertThat(table.get(2).points()).isEqualTo(5);

		var groupOf = new HashMap<UUID, UUID>(Map.of(E.get(0), JOY, E.get(1), HOPE));
		assertThat(Standings.groupsOf(List.of(E.get(1), E.get(0), E.get(5)), groupOf)).containsExactly(HOPE, JOY, null);
	}

	@Test
	void placesAreSaidTheUsualWay() {
		assertThat(List.of(1, 2, 3, 4, 11, 12, 13, 21, 22)).extracting(Standings::ordinal)
			.containsExactly("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd");
	}

}
