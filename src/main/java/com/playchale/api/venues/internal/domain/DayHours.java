package com.playchale.api.venues.internal.domain;

import java.util.regex.Pattern;

import com.playchale.api.shared.error.BusinessException;

/**
 * One day's opening hours in the venue's local time, e.g. 06:00 to 23:00. A venue can close at
 * midnight, written 24:00.
 */
public record DayHours(String open, String close) {

	private static final Pattern CLOCK = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

	private static final String MIDNIGHT = "24:00";

	public DayHours {
		if (open == null || close == null || !CLOCK.matcher(open).matches()
				|| !(CLOCK.matcher(close).matches() || MIDNIGHT.equals(close))) {
			throw BusinessException.invalid("The opening hours aren’t valid. Use times like 06:00 and 22:00.");
		}
		if (minutes(close) - minutes(open) < 60) {
			throw BusinessException.invalid("Each open day needs at least an hour between opening and closing.");
		}
	}

	/** Minutes after local midnight that it opens. */
	public int openMinute() {
		return minutes(open);
	}

	/** Minutes after local midnight that it closes (1440 for midnight). */
	public int closeMinute() {
		return minutes(close);
	}

	/** How it's stored: "06:00-23:00". */
	String stored() {
		return open + "-" + close;
	}

	static DayHours fromStored(String stored) {
		var parts = stored.split("-");
		return new DayHours(parts[0], parts[1]);
	}

	private static int minutes(String clock) {
		return Integer.parseInt(clock.substring(0, 2)) * 60 + Integer.parseInt(clock.substring(3));
	}

}
