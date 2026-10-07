package com.playchale.api.games.internal.domain;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** When a repeating game's games are: the dates, the clock changes, and the months with five Saturdays. */
class SeriesRuleTest {

	private static final ZoneId ACCRA = ZoneId.of("Africa/Accra");

	private static final ZoneId LONDON = ZoneId.of("Europe/London");

	/** Saturday 10 October 2026, 6pm in Accra (UTC all year). */
	private static final Instant SAT_10_OCT = Instant.parse("2026-10-10T18:00:00Z");

	private static LocalDate day(String date) {
		return LocalDate.parse(date);
	}

	@Test
	void aGameMovedToAnotherDayCarriesOnTheWeekAfterNotTheNextMorning() {
		var sundays = new SeriesRule(SeriesRule.WEEKLY, DayOfWeek.SUNDAY, null, LocalTime.of(17, 0), ACCRA);
		assertThat(sundays.after(day("2026-10-10"))).as("a Saturday game, then Sundays").isEqualTo(day("2026-10-18"));
		var fridays = new SeriesRule(SeriesRule.WEEKLY, DayOfWeek.FRIDAY, null, LocalTime.of(17, 0), ACCRA);
		assertThat(fridays.after(day("2026-10-10"))).as("a Saturday game, then Fridays").isEqualTo(day("2026-10-16"));
		assertThat(sundays.onOrAfter(day("2026-10-14"))).as("with no game before it, the soonest").isEqualTo(day("2026-10-18"));
	}

	@Test
	void everyWeekIsTheSameDayAWeekOn() {
		var rule = SeriesRule.of(SeriesRule.WEEKLY, null, SAT_10_OCT, ACCRA);
		assertThat(rule.weekday()).isEqualTo(DayOfWeek.SATURDAY);
		assertThat(rule.kickOff()).isEqualTo(LocalTime.of(18, 0));
		assertThat(rule.after(day("2026-10-10"))).isEqualTo(day("2026-10-17"));
		assertThat(rule.describe()).isEqualTo("Every Saturday");
	}

	@Test
	void everyOtherWeekSkipsOne() {
		var rule = SeriesRule.of(SeriesRule.FORTNIGHTLY, null, SAT_10_OCT, ACCRA);
		assertThat(rule.after(day("2026-10-10"))).isEqualTo(day("2026-10-24"));
		// Carried on from a game on another day (the host just moved it to Saturdays): two weeks on, near enough.
		assertThat(rule.after(day("2026-10-13"))).isEqualTo(day("2026-10-31"));
		assertThat(rule.describe()).isEqualTo("Every other Saturday");
	}

	@Test
	void monthlyIsTheSameWeekdayOfTheMonthNotTheSameDate() {
		// 10 October 2026 is the 2nd Saturday; the 2nd Saturday of November is the 14th, not the 10th.
		var rule = SeriesRule.of(SeriesRule.MONTHLY, 2, SAT_10_OCT, ACCRA);
		assertThat(rule.after(day("2026-10-10"))).isEqualTo(day("2026-11-14"));
		assertThat(rule.after(day("2026-11-14"))).isEqualTo(day("2026-12-12"));
		assertThat(rule.describe()).isEqualTo("2nd Saturday of the month");
	}

	@Test
	void theLastSaturdayIsTheLastWhetherTheMonthHasFourOrFive() {
		// October 2026 has five Saturdays (the 31st is the last); November has four (the 28th).
		var rule = SeriesRule.of(SeriesRule.MONTHLY, SeriesRule.LAST, Instant.parse("2026-10-31T09:00:00Z"), ACCRA);
		assertThat(rule.after(day("2026-10-31"))).isEqualTo(day("2026-11-28"));
		assertThat(rule.after(day("2026-11-28"))).isEqualTo(day("2026-12-26"));
		assertThat(rule.describe()).isEqualTo("Last Saturday of the month");
	}

	@Test
	void theFourthIsNotTheLastInAMonthWithFive() {
		var fourth = SeriesRule.of(SeriesRule.MONTHLY, 4, Instant.parse("2026-10-24T09:00:00Z"), ACCRA);
		assertThat(fourth.after(day("2026-10-24"))).isEqualTo(day("2026-11-28"));
		assertThat(fourth.matches(day("2026-10-31"))).isFalse();
	}

	@Test
	void aMonthlyRuleHasToDescribeTheFirstGame() {
		assertThatThrownBy(() -> SeriesRule.of(SeriesRule.MONTHLY, 1, SAT_10_OCT, ACCRA))
			.hasMessage("10 October isn’t the 1st Saturday of the month. Pick that day, or another week of the month.");
		assertThatThrownBy(() -> SeriesRule.of(SeriesRule.MONTHLY, null, SAT_10_OCT, ACCRA))
			.hasMessage("Choose which week of the month it’s played.");
		assertThatThrownBy(() -> SeriesRule.of("daily", null, SAT_10_OCT, ACCRA)).hasMessage("Choose how often it repeats.");
	}

	@Test
	void kickOffStaysTheSameLocalTimeWhenTheClocksChange() {
		// London: 6pm on Saturday 24 October is 17:00 UTC (BST); the clocks go back on the 25th, so the
		// next 6pm is 18:00 UTC. The game stays at 6pm on the wall clock.
		var rule = SeriesRule.of(SeriesRule.WEEKLY, null, Instant.parse("2026-10-24T17:00:00Z"), LONDON);
		assertThat(rule.at(rule.after(day("2026-10-24")))).isEqualTo(Instant.parse("2026-10-31T18:00:00Z"));
	}

	@Test
	void aDateTooCloseOrGoneRollsOnToTheFirstOneFarEnoughAway() {
		var rule = SeriesRule.of(SeriesRule.WEEKLY, null, SAT_10_OCT, ACCRA);
		var planned = Instant.parse("2026-10-17T18:00:00Z");
		// Far enough away: as planned.
		assertThat(rule.onOrAfter(planned, Instant.parse("2026-10-12T00:00:00Z"))).isEqualTo(planned);
		// Opened ten days late (the API was down, or the host repeated an old game): the Saturday after.
		var late = Instant.parse("2026-10-20T10:00:00Z").plus(Duration.ofHours(12));
		assertThat(rule.onOrAfter(planned, late)).isEqualTo(Instant.parse("2026-10-24T18:00:00Z"));
		// Six hours before kick-off is too close for a 12-hour lead: the week after.
		assertThat(rule.onOrAfter(planned, Instant.parse("2026-10-17T12:00:00Z").plus(Duration.ofHours(12))))
			.isEqualTo(Instant.parse("2026-10-24T18:00:00Z"));
	}

}
