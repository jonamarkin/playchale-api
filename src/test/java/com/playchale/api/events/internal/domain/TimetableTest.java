package com.playchale.api.events.internal.domain;

import java.time.Instant;
import java.util.List;

import com.playchale.api.events.internal.domain.Timetable.Plan;
import com.playchale.api.events.internal.domain.Timetable.Slot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Turning a coordinator's plan into a time and a place for every match and heat. */
class TimetableTest {

	private static final Instant TEN = Instant.parse("2030-06-01T10:00:00Z");

	private static Instant at(String clock) {
		return Instant.parse("2030-06-01T" + clock + ":00Z");
	}

	@Test
	void aWaveFillsThePlacesSideBySideAndTakesTurnsWhenThereAreMore() {
		var plan = new Plan(TEN, 15, List.of("Court 1", "Court 2"));
		var waves = List.of(3, 2);
		assertThat(Timetable.slot(plan, waves, 0, 0)).isEqualTo(new Slot(TEN, "Court 1"));
		assertThat(Timetable.slot(plan, waves, 0, 1)).isEqualTo(new Slot(TEN, "Court 2"));
		assertThat(Timetable.slot(plan, waves, 0, 2)).isEqualTo(new Slot(at("10:15"), "Court 1"));
		// The next round waits for the last of this one.
		assertThat(Timetable.slot(plan, waves, 1, 0)).isEqualTo(new Slot(at("10:30"), "Court 1"));
		assertThat(Timetable.slot(plan, waves, 1, 1)).isEqualTo(new Slot(at("10:30"), "Court 2"));
	}

	@Test
	void oneTableMeansOneMatchAtATime() {
		var plan = new Plan(TEN, 20, List.of("Table 1"));
		assertThat(Timetable.slot(plan, List.of(2, 1), 1, 0)).isEqualTo(new Slot(at("10:40"), "Table 1"));
	}

	@Test
	void aKnockoutsByesArentPlayedAndItsThirdPlaceMatchComesBeforeTheFinal() {
		// Six in a bracket of eight: two byes, so two real ties in the first round, then semis, then the last.
		assertThat(Timetable.knockoutWaves(4, 2, false)).containsExactly(2, 2, 1);
		assertThat(Timetable.knockoutWaves(4, 2, true)).containsExactly(2, 2, 2);
		assertThat(Timetable.knockoutWaves(1, 1, true)).containsExactly(1);

		var plan = new Plan(TEN, 30, List.of("Pitch"));
		var waves = Timetable.knockoutWaves(2, 2, true);
		// Round 2 is the last: the match for third, then the final.
		assertThat(Timetable.slot(plan, waves, 1, Timetable.knockoutIndex(2, 0, true, 2, true, 0))).isEqualTo(new Slot(at("11:00"), "Pitch"));
		assertThat(Timetable.slot(plan, waves, 1, Timetable.knockoutIndex(2, 0, false, 2, true, 0))).isEqualTo(new Slot(at("11:30"), "Pitch"));
	}

	@Test
	void heatsThenTheirFinal() {
		var plan = new Plan(TEN, 10, List.of("Board 1", "Board 2"));
		var waves = Timetable.heatWaves(3);
		assertThat(Timetable.slot(plan, waves, 0, 2)).isEqualTo(new Slot(at("10:10"), "Board 1"));
		assertThat(Timetable.slot(plan, waves, 1, 0)).isEqualTo(new Slot(at("10:20"), "Board 1"));
	}

}
