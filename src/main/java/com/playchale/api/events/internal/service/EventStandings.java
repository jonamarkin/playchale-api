package com.playchale.api.events.internal.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.playchale.api.events.internal.domain.Standings;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What's been played in an event, and what it adds up to: each game's matches or heats, a league's
 * table, a decided game's places, and the overall table of groups. Worked out on every read from the
 * results themselves, so a correction anywhere is reflected everywhere at once.
 */
@Component
class EventStandings {

	private final JdbcClient jdbc;

	EventStandings(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Everything played in an event, by game. */
	record Play(Map<UUID, List<EventViews.Match>> matches, Map<UUID, List<EventViews.Heat>> heats) {
	}

	Play load(UUID eventId) {
		var sets = new HashMap<UUID, List<EventViews.SetScore>>();
		jdbc.sql("""
				SELECT s.match_id, s.home, s.away FROM event_match_sets s JOIN event_matches m ON m.id = s.match_id
				JOIN event_games g ON g.id = m.game_id WHERE g.event_id = :event ORDER BY s.match_id, s.set_no
				""").param("event", eventId).query((rs, n) -> {
				sets.computeIfAbsent((UUID) rs.getObject(1), k -> new ArrayList<>()).add(new EventViews.SetScore(rs.getInt(2), rs.getInt(3)));
				return null;
			}).list();
		var matches = new HashMap<UUID, List<EventViews.Match>>();
		jdbc.sql("""
				SELECT m.* FROM event_matches m JOIN event_games g ON g.id = m.game_id WHERE g.event_id = :event
				ORDER BY m.third_place, m.round, m.slot
				""").param("event", eventId).query((rs, n) -> {
				var id = (UUID) rs.getObject("id");
				matches.computeIfAbsent((UUID) rs.getObject("game_id"), k -> new ArrayList<>()).add(new EventViews.Match(id, rs.getInt("round"),
						rs.getInt("slot"), rs.getBoolean("third_place"), (UUID) rs.getObject("home_entry_id"), (UUID) rs.getObject("away_entry_id"),
						integer(rs, "home_score"), integer(rs, "away_score"), sets.getOrDefault(id, List.of()), (UUID) rs.getObject("winner_entry_id"),
						rs.getString("decided_by"), integer(rs, "home_penalties"), integer(rs, "away_penalties"), instant(rs, "starts_at"),
						rs.getString("location"), instant(rs, "recorded_at")));
				return null;
			}).list();
		var lanes = new HashMap<UUID, List<EventViews.Lane>>();
		jdbc.sql("""
				SELECT he.heat_id, he.entry_id, he.lane, he.place, he.mark FROM event_heat_entries he JOIN event_heats h ON h.id = he.heat_id
				JOIN event_games g ON g.id = h.game_id WHERE g.event_id = :event ORDER BY he.lane
				""").param("event", eventId).query((rs, n) -> {
				lanes.computeIfAbsent((UUID) rs.getObject(1), k -> new ArrayList<>())
					.add(new EventViews.Lane((UUID) rs.getObject(2), rs.getInt(3), integer(rs, "place"), rs.getString(5)));
				return null;
			}).list();
		var heats = new HashMap<UUID, List<EventViews.Heat>>();
		jdbc.sql("""
				SELECT h.* FROM event_heats h JOIN event_games g ON g.id = h.game_id WHERE g.event_id = :event
				ORDER BY CASE h.stage WHEN 'heat' THEN 0 ELSE 1 END, h.number
				""").param("event", eventId).query((rs, n) -> {
				var id = (UUID) rs.getObject("id");
				heats.computeIfAbsent((UUID) rs.getObject("game_id"), k -> new ArrayList<>()).add(new EventViews.Heat(id, rs.getString("stage"),
						rs.getInt("number"), lanes.getOrDefault(id, List.of()), instant(rs, "starts_at"), rs.getString("location"),
						instant(rs, "recorded_at")));
				return null;
			}).list();
		return new Play(matches, heats);
	}

	/** A game with what's been played in it, its league table, and its places once it's decided. */
	EventViews.Game withPlay(EventViews.Game game, Play play) {
		var matches = play.matches().getOrDefault(game.id(), List.of());
		var heats = play.heats().getOrDefault(game.id(), List.of());
		var table = "league".equals(game.format()) ? table(game, matches) : null;
		var places = "finished".equals(game.status()) ? places(game, matches, heats, table) : List.<UUID>of();
		return game.withPlay(matches, heats, table, places);
	}

	/** A league's table so far: everyone entered, played or not. */
	List<EventViews.LeagueRow> table(EventViews.Game game, List<EventViews.Match> matches) {
		var entries = game.entries().stream().filter(e -> "entered".equals(e.status())).map(EventViews.Entry::id).toList();
		var keepsScore = !"outcome".equals(game.scoring());
		var played = matches.stream().filter(m -> m.recordedAt() != null && m.homeEntryId() != null && m.awayEntryId() != null)
			.map(m -> new Standings.Played(m.homeEntryId(), m.awayEntryId(), keepsScore && m.homeScore() != null ? m.homeScore() : 0,
					keepsScore && m.awayScore() != null ? m.awayScore() : 0, m.winnerEntryId()))
			.toList();
		return Standings.table(entries, played).stream().map(r -> new EventViews.LeagueRow(r.entry(), r.played(), r.won(), r.drawn(),
				r.lost(), r.scored(), r.conceded(), r.points())).toList();
	}

	/** Who finished where in a decided game: 1st, 2nd, 3rd and on, as far as the format says. */
	List<UUID> places(EventViews.Game game, List<EventViews.Match> matches, List<EventViews.Heat> heats, List<EventViews.LeagueRow> table) {
		switch (game.format()) {
			case "league" -> {
				return table == null ? List.of() : table.stream().map(EventViews.LeagueRow::entryId).toList();
			}
			case "knockout" -> {
				var rounds = rounds(matches);
				var places = new ArrayList<UUID>();
				matches.stream().filter(m -> !m.thirdPlace() && m.round() == rounds && m.slot() == 0).findFirst()
					.ifPresent(f -> addWinnerThenLoser(places, f));
				matches.stream().filter(EventViews.Match::thirdPlace).findFirst().ifPresent(t -> addWinnerThenLoser(places, t));
				return places;
			}
			default -> {
				return heats.stream().filter(h -> "final".equals(h.stage())).findFirst()
					.map(f -> f.lanes().stream().filter(l -> l.place() != null).sorted(Comparator.comparingInt(EventViews.Lane::place))
						.map(EventViews.Lane::entryId).toList())
					.orElse(List.of());
			}
		}
	}

	private static void addWinnerThenLoser(List<UUID> places, EventViews.Match match) {
		if (match.winnerEntryId() == null) {
			return;
		}
		places.add(match.winnerEntryId());
		var loser = match.winnerEntryId().equals(match.homeEntryId()) ? match.awayEntryId() : match.homeEntryId();
		if (loser != null) {
			places.add(loser);
		}
	}

	/** How many rounds a knockout has: the first round holds half the bracket, byes included. */
	static int rounds(List<EventViews.Match> matches) {
		var first = (int) matches.stream().filter(m -> !m.thirdPlace() && m.round() == 1).count();
		return first == 0 ? 0 : Integer.numberOfTrailingZeros(Integer.highestOneBit(first * 2));
	}

	/** The overall table: every decided game's places, worth the event's points to their groups. */
	List<EventViews.GroupRow> groupTable(List<EventViews.Group> groups, List<Integer> points, List<EventViews.Game> games) {
		var groupIds = groups.stream().map(EventViews.Group::id).toList();
		var places = new ArrayList<List<UUID>>();
		for (var game : games) {
			if (game.places() == null || game.places().isEmpty()) {
				continue;
			}
			var groupOf = new HashMap<UUID, UUID>();
			game.entries().forEach(e -> {
				if (e.groupId() != null) {
					groupOf.put(e.id(), e.groupId());
				}
			});
			places.add(Standings.groupsOf(game.places(), groupOf));
		}
		return Standings.groups(groupIds, points, places).stream()
			.map(r -> new EventViews.GroupRow(r.group(), r.points(), r.gold(), r.silver(), r.bronze())).toList();
	}

	/** The entry's name, for saying what happened. */
	static String nameOf(EventViews.Game game, UUID entryId) {
		return game.entries().stream().filter(e -> Objects.equals(e.id(), entryId)).map(EventViews.Entry::name).findFirst().orElse("Someone");
	}

	private static Integer integer(ResultSet rs, String column) throws SQLException {
		var value = rs.getObject(column);
		return value == null ? null : ((Number) value).intValue();
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		var value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

}
