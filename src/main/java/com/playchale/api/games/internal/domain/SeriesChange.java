package com.playchale.api.games.internal.domain;

import java.time.LocalTime;

/**
 * What a host can change about a repeating game: when it's played and what its games are. Not where:
 * moving to another venue is a different game, so that's stopping this one and starting another.
 *
 * @param weekday     ISO: 1 is Monday
 * @param weekOfMonth monthly only: 1 to 4, or -1 for the last
 * @param title       blank keeps the current one
 */
public record SeriesChange(String title, int weekday, LocalTime kickOff, String frequency, Integer weekOfMonth, int durationMinutes,
		int capacity, long totalCost, String pricing, String visibility, String notes) {
}
