package com.playchale.api.games.internal.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Set;

import com.playchale.api.shared.error.BusinessException;

/**
 * When a repeating game is played: every week, every other week, or once a month on the same weekday
 * ("the 2nd Saturday", "the last Sunday"). Not a date of the month: the 15th is a Saturday one month
 * and a Tuesday the next, and a group plays on a day of the week.
 *
 * <p>Kick-off is local to {@code zone}, where the game is played, so 6pm stays 6pm when the clocks
 * change. Nothing here reads the clock; every answer follows from its arguments.
 *
 * @param weekOfMonth monthly only: 1 to 4, or {@link #LAST}. There is no 5th: most months don't have one.
 */
public record SeriesRule(String frequency, DayOfWeek weekday, Integer weekOfMonth, LocalTime kickOff, ZoneId zone) {

	public static final String WEEKLY = "weekly";

	public static final String FORTNIGHTLY = "fortnightly";

	public static final String MONTHLY = "monthly";

	public static final int LAST = -1;

	private static final Set<String> FREQUENCIES = Set.of(WEEKLY, FORTNIGHTLY, MONTHLY);

	private static final Set<Integer> WEEKS = Set.of(LAST, 1, 2, 3, 4);

	private static final String[] ORDINALS = { "1st", "2nd", "3rd", "4th" };

	public SeriesRule {
		if (frequency == null || !FREQUENCIES.contains(frequency)) {
			throw BusinessException.invalid("Choose how often it repeats.");
		}
		if (weekday == null || kickOff == null || zone == null) {
			throw BusinessException.invalid("Pick the day and time it’s played.");
		}
		if (MONTHLY.equals(frequency) && (weekOfMonth == null || !WEEKS.contains(weekOfMonth))) {
			throw BusinessException.invalid("Choose which week of the month it’s played.");
		}
		weekOfMonth = MONTHLY.equals(frequency) ? weekOfMonth : null;
	}

	/**
	 * The rule for a game first played at {@code first}: its weekday and kick-off, where it's played.
	 * A monthly rule has to describe that first date, or the series would skip straight past it.
	 */
	public static SeriesRule of(String frequency, Integer weekOfMonth, Instant first, ZoneId zone) {
		var local = first.atZone(zone);
		var rule = new SeriesRule(frequency, local.getDayOfWeek(), weekOfMonth, local.toLocalTime(), zone);
		if (!rule.matches(local.toLocalDate())) {
			var said = rule.describe();
			throw BusinessException.invalid("%d %s isn’t the %s. Pick that day, or another week of the month.".formatted(local.getDayOfMonth(),
					local.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH), Character.toLowerCase(said.charAt(0)) + said.substring(1)));
		}
		return rule;
	}

	/** Whether a game on {@code date} is one of this series' days. */
	public boolean matches(LocalDate date) {
		if (date.getDayOfWeek() != weekday) {
			return false;
		}
		return !MONTHLY.equals(frequency) || inMonth(YearMonth.from(date)).equals(date);
	}

	/**
	 * The series' next day after a game on {@code date}: a whole week, two weeks or month on. {@code date}
	 * needn't be one of its days. A Saturday game moved to Sundays carries on the Sunday of the week
	 * after, not the next morning.
	 */
	public LocalDate after(LocalDate date) {
		return onOrAfter(date.plusDays(switch (frequency) {
			case WEEKLY -> 6;
			case FORTNIGHTLY -> 13;
			default -> 1;
		}));
	}

	/** The first of the series' days on or after {@code date}: the soonest it can be played, with nothing before it. */
	public LocalDate onOrAfter(LocalDate date) {
		if (!MONTHLY.equals(frequency)) {
			return date.with(TemporalAdjusters.nextOrSame(weekday));
		}
		var thisMonth = inMonth(YearMonth.from(date));
		return thisMonth.isBefore(date) ? inMonth(YearMonth.from(date).plusMonths(1)) : thisMonth;
	}

	/** Kick-off on {@code date}. A time the clocks skip over (spring forward) moves on by the gap, as a wall clock would. */
	public Instant at(LocalDate date) {
		return ZonedDateTime.of(date, kickOff, zone).toInstant();
	}

	/** The first game after the one at {@code from} that starts no earlier than {@code earliest}. */
	public Instant firstAfter(Instant from, Instant earliest) {
		var day = from.atZone(zone).toLocalDate();
		do {
			day = after(day);
		}
		while (at(day).isBefore(earliest));
		return at(day);
	}

	/** {@code planned}, or when that's too soon (or gone), the first of the series' days after it that isn't. */
	public Instant onOrAfter(Instant planned, Instant earliest) {
		return planned.isBefore(earliest) ? firstAfter(planned, earliest) : planned;
	}

	/** "Every Saturday", "Every other Saturday", "2nd Saturday of the month", "Last Saturday of the month". */
	public String describe() {
		var day = weekday.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
		return switch (frequency) {
			case WEEKLY -> "Every " + day;
			case FORTNIGHTLY -> "Every other " + day;
			default -> weekOfMonth == LAST ? "Last %s of the month".formatted(day)
					: "%s %s of the month".formatted(ORDINALS[weekOfMonth - 1], day);
		};
	}

	/** This series' day in {@code month}: the nth (or last) of its weekday there. Every month has a 4th and a last. */
	private LocalDate inMonth(YearMonth month) {
		return weekOfMonth == LAST ? month.atEndOfMonth().with(TemporalAdjusters.lastInMonth(weekday))
				: month.atDay(1).with(TemporalAdjusters.dayOfWeekInMonth(weekOfMonth, weekday));
	}

}
