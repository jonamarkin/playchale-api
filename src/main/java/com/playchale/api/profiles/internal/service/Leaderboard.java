package com.playchale.api.profiles.internal.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

import com.playchale.api.games.api.PlayerTotals;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.users.api.UserSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who is top, by what they actually did, over the games a filter allows.
 *
 * <p>This ranks play, not people. Goals, wins and games are what a sport has always counted, and a
 * top-scorer table is as old as leagues; it is a different thing from scoring someone's character,
 * which RELIABILITY.md argues against and which nothing here does.
 *
 * <p>It ranks totals rather than averages on purpose. A rate off two games says nothing, and the
 * moment a table rewards a ratio it rewards playing less — the opposite of the point.
 */
@Service
public class Leaderboard {

	/**
	 * The windows a table can cover.
	 *
	 * <p>Plain calendar periods on purpose. "This season" would be a borrowed word with nothing
	 * behind it here: a season belongs to a competition, whose organiser sets when it starts and
	 * ends, and this table spans pickup games across sports and countries that are in no season at
	 * all. A competition's own table is where a season means something.
	 */
	public enum Period {

		MONTH, YEAR, ALL

	}

	private final PlayerTotals totals;

	private final UserDirectory users;

	private final Clock clock;

	Leaderboard(PlayerTotals totals, UserDirectory users, Clock clock) {
		this.totals = totals;
		this.users = users;
		this.clock = clock;
	}

	/**
	 * One row of the table.
	 *
	 * @param place 1 for the top, and shared by players who are level on the metric
	 */
	public record Standing(int place, UserSummary player, int games, int wins, int goals, int assists, int points, int sets) {
	}

	@Transactional(readOnly = true)
	public List<Standing> top(String sport, String country, String area, Period period, String metric, int limit) {
		var measure = measure(metric);
		var rows = totals.top(new PlayerTotals.Filter(sport, country, area, since(period), measure, limit));
		if (rows.isEmpty()) {
			return List.of();
		}
		var people = users.findAll(rows.stream().map(PlayerTotals.Totals::userId).toList());

		var standings = new java.util.ArrayList<Standing>(rows.size());
		int place = 0;
		long previous = Long.MIN_VALUE;
		for (int i = 0; i < rows.size(); i++) {
			var row = rows.get(i);
			var player = people.get(row.userId());
			if (player == null) {
				continue;
			}
			var score = scoreOf(row, measure);
			// Level on the metric means level on the table: two players on nine goals are both third.
			if (score != previous) {
				place = i + 1;
				previous = score;
			}
			standings.add(new Standing(place, player.toPublic(), row.games(), row.wins(), row.goals(), row.assists(), row.points(), row.sets()));
		}
		return List.copyOf(standings);
	}

	/** The areas a filter can offer, which is only ever places games have actually been played. */
	@Transactional(readOnly = true)
	public List<String> areas(String country) {
		return totals.areas(country);
	}

	private static long scoreOf(PlayerTotals.Totals row, PlayerTotals.Metric metric) {
		return switch (metric) {
			case GOALS -> row.goals();
			case ASSISTS -> row.assists();
			case POINTS -> row.points();
			case SETS -> row.sets();
			case WINS -> row.wins();
			case GAMES -> row.games();
		};
	}

	private static PlayerTotals.Metric measure(String metric) {
		if (metric == null || metric.isBlank()) {
			return PlayerTotals.Metric.GOALS;
		}
		try {
			return PlayerTotals.Metric.valueOf(metric.strip().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException unknown) {
			return PlayerTotals.Metric.GOALS;
		}
	}

	/**
	 * The start of the period, not a rolling window: "this month" means since the first, as anyone
	 * reading it would expect, rather than thirty days back. Counted in UTC, which is Ghana's own
	 * clock; elsewhere the boundary can sit a few hours out, which no leaderboard turns on.
	 */
	private Instant since(Period period) {
		if (period == null || period == Period.ALL) {
			return null;
		}
		var today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
		var from = period == Period.MONTH ? today.withDayOfMonth(1) : today.withDayOfYear(1);
		return from.atStartOfDay(ZoneOffset.UTC).toInstant();
	}

}
