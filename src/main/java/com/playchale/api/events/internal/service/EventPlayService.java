package com.playchale.api.events.internal.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import com.playchale.api.events.api.EventActivity;
import com.playchale.api.events.internal.domain.ResultRules;
import com.playchale.api.events.internal.domain.Standings;
import com.playchale.api.events.internal.service.EventAccess.GameRow;
import com.playchale.api.shared.draws.Knockout;
import com.playchale.api.shared.draws.RoundRobin;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.shared.events.Happened;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Playing an event's games: the draw, the results, and finishing the event.
 *
 * <p>A knockout's later rounds are rebuilt from the winners every time a result changes, so a
 * correction flows through by itself. What can't be undone is refused: a result can only change
 * while the round after it hasn't been played, and a draw can only be made again before any result.
 */
@Service
public class EventPlayService {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcClient jdbc;

	private final Clock clock;

	private final EventAccess access;

	private final EventReader reader;

	private final Happened happened;

	private final ApplicationEventPublisher events;

	EventPlayService(JdbcClient jdbc, Clock clock, EventAccess access, EventReader reader, Happened happened,
			ApplicationEventPublisher events) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.access = access;
		this.reader = reader;
		this.happened = happened;
		this.events = events;
	}

	/** One finishing place in a heat, as the coordinator sends it. */
	public record Placing(UUID entryId, Integer place, String mark) {
	}

	/** What a game is played with, beyond what the rules turn on. */
	private record Settings(Integer bestOf, boolean drawsAllowed, boolean thirdPlace, Integer heatSize, Integer advance, String location,
			Instant startsAt) {
	}

	private record MatchRow(UUID id, UUID gameId, int round, int slot, boolean thirdPlace, UUID home, UUID away, UUID winner,
			boolean recorded) {

		UUID loser() {
			return winner == null || home == null || away == null ? null : winner.equals(home) ? away : home;
		}

	}

	private record HeatRow(UUID id, UUID gameId, String stage, int number, boolean recorded) {

		boolean isFinal() {
			return "final".equals(stage);
		}

	}

	/* ------------------------------------------------------------------ */
	/* The draw                                                             */
	/* ------------------------------------------------------------------ */

	/** Makes the draw, at random: a knockout bracket, a league's fixtures, or heats. */
	@Transactional
	public EventViews.Detail draw(UUID eventId, UUID gameId, UUID userId) {
		var game = access.requireRunner(eventId, gameId, userId);
		requireOn(eventId);
		if (!game.open()) {
			throw BusinessException.conflict("The draw for %s is already made.".formatted(game.name()));
		}
		var entries = jdbc.sql("SELECT id FROM event_entries WHERE game_id = :game AND status = 'entered' ORDER BY seed")
			.param("game", gameId).query(UUID.class).list();
		if (entries.size() < 2) {
			throw BusinessException.invalid("A draw needs at least two in it.");
		}
		var order = new ArrayList<>(entries);
		Collections.shuffle(order, RANDOM);
		var settings = settings(gameId);
		switch (game.format()) {
			case "knockout" -> {
				var first = Knockout.firstRound(order);
				first.ties().forEach(t -> insertMatch(gameId, 1, t.slot(), false, t.home(), t.away(), null));
				first.byes().forEach((slot, entry) -> insertMatch(gameId, 1, slot, false, entry, null, entry));
				rebuild(game, settings);
			}
			case "league" -> {
				var rounds = RoundRobin.rounds(order);
				for (int r = 0; r < rounds.size(); r++) {
					for (int s = 0; s < rounds.get(r).size(); s++) {
						var pairing = rounds.get(r).get(s);
						insertMatch(gameId, r + 1, s, false, pairing.home(), pairing.away(), null);
					}
				}
			}
			default -> {
				var heats = Standings.heats(order, settings.heatSize());
				var stage = heats.size() == 1 ? "final" : "heat";
				for (int i = 0; i < heats.size(); i++) {
					insertHeat(gameId, stage, i + 1, heats.get(i));
				}
			}
		}
		jdbc.sql("UPDATE event_games SET status = 'drawn', updated_at = :now WHERE id = :id").param("now", now()).param("id", gameId).update();
		var event = access.event(eventId);
		happened.record("event.game-drawn", "event", eventId, userId, event.organisationId(), null,
				Map.of("format", game.format(), "entries", entries.size()));
		tellFirstUp(eventId, gameId, userId);
		return reader.detail(eventId, userId);
	}

	/** Takes the draw back, before anything's been played, so it can be made again with whoever's in now. */
	@Transactional
	public EventViews.Detail undraw(UUID eventId, UUID gameId, UUID userId) {
		var game = access.requireRunner(eventId, gameId, userId);
		requireOn(eventId);
		if (game.open()) {
			return reader.detail(eventId, userId);
		}
		var played = jdbc.sql("""
				SELECT (SELECT count(*) FROM event_matches WHERE game_id = :game AND recorded_at IS NOT NULL)
				     + (SELECT count(*) FROM event_heats WHERE game_id = :game AND recorded_at IS NOT NULL)
				""").param("game", gameId).query(Integer.class).single();
		if (played > 0) {
			throw BusinessException.conflict("Results are in for %s, so its draw stands.".formatted(game.name()));
		}
		jdbc.sql("DELETE FROM event_matches WHERE game_id = :game").param("game", gameId).update();
		jdbc.sql("DELETE FROM event_heats WHERE game_id = :game").param("game", gameId).update();
		jdbc.sql("UPDATE event_games SET status = 'open', updated_at = :now WHERE id = :id").param("now", now()).param("id", gameId).update();
		return reader.detail(eventId, userId);
	}

	/* ------------------------------------------------------------------ */
	/* Results                                                              */
	/* ------------------------------------------------------------------ */

	/** Records a match's result, or corrects it while nothing after it has been played. */
	@Transactional
	public EventViews.Detail recordMatch(UUID eventId, UUID matchId, ResultRules.Asked asked, UUID userId) {
		var match = match(eventId, matchId);
		var game = access.requireRunner(eventId, match.gameId(), userId);
		requireOn(eventId);
		if (match.home() == null || match.away() == null) {
			throw BusinessException.conflict("Both sides need to be known first.");
		}
		var settings = settings(game.id());
		if (match.recorded()) {
			requireCorrectable(game, match);
		}
		var knockout = "knockout".equals(game.format());
		var result = ResultRules.settle(game.scoring(), settings.bestOf(), knockout, settings.drawsAllowed(), asked);
		var winner = result.winner() == null ? null : result.winner() == ResultRules.Side.HOME ? match.home() : match.away();
		jdbc.sql("""
				UPDATE event_matches SET home_score = :hs, away_score = :as, winner_entry_id = :winner, decided_by = :by,
				       home_penalties = :hp, away_penalties = :ap, recorded_by = :user, recorded_at = :now
				WHERE id = :id
				""").param("hs", result.homeScore()).param("as", result.awayScore()).param("winner", winner).param("by", result.decidedBy())
			.param("hp", result.homePenalties()).param("ap", result.awayPenalties()).param("user", userId).param("now", now())
			.param("id", matchId).update();
		jdbc.sql("DELETE FROM event_match_sets WHERE match_id = :id").param("id", matchId).update();
		for (int i = 0; i < result.sets().size(); i++) {
			var set = result.sets().get(i);
			jdbc.sql("INSERT INTO event_match_sets (match_id, set_no, home, away) VALUES (:id, :no, :home, :away)").param("id", matchId)
				.param("no", i + 1).param("home", set.home()).param("away", set.away()).update();
		}
		if (knockout) {
			rebuild(game, settings);
		}
		settle(game);
		var detail = reader.detail(eventId, userId);
		tell(detail, game.id(), List.of(match.home(), match.away()), userId, played -> summary(played, matchView(played, matchId)));
		return detail;
	}

	/** Takes a result back, while nothing after it has been played. */
	@Transactional
	public EventViews.Detail clearMatch(UUID eventId, UUID matchId, UUID userId) {
		var match = match(eventId, matchId);
		var game = access.requireRunner(eventId, match.gameId(), userId);
		requireOn(eventId);
		if (!match.recorded()) {
			return reader.detail(eventId, userId);
		}
		requireCorrectable(game, match);
		jdbc.sql("""
				UPDATE event_matches SET home_score = NULL, away_score = NULL, winner_entry_id = NULL, decided_by = NULL,
				       home_penalties = NULL, away_penalties = NULL, recorded_by = NULL, recorded_at = NULL
				WHERE id = :id
				""").param("id", matchId).update();
		jdbc.sql("DELETE FROM event_match_sets WHERE match_id = :id").param("id", matchId).update();
		if ("knockout".equals(game.format())) {
			rebuild(game, settings(game.id()));
		}
		settle(game);
		return reader.detail(eventId, userId);
	}

	/**
	 * Records the finishing order of a heat or a final. When the last heat is in, the final is made
	 * from the best of each; correcting a heat remakes a final that hasn't been run.
	 */
	@Transactional
	public EventViews.Detail recordHeat(UUID eventId, UUID heatId, List<Placing> placings, UUID userId) {
		var heat = heat(eventId, heatId);
		var game = access.requireRunner(eventId, heat.gameId(), userId);
		requireOn(eventId);
		if (!heat.isFinal() && finalRun(game.id())) {
			throw BusinessException.conflict("The final has been run, so the heats stand.");
		}
		var lanes = jdbc.sql("SELECT entry_id FROM event_heat_entries WHERE heat_id = :heat ORDER BY lane").param("heat", heatId)
			.query(UUID.class).list();
		var places = new HashMap<UUID, Integer>();
		var marks = new HashMap<UUID, String>();
		for (var p : placings == null ? List.<Placing>of() : placings) {
			if (p.entryId() != null) {
				places.put(p.entryId(), p.place());
				var mark = p.mark() == null ? "" : p.mark().strip();
				if (mark.length() > 20) {
					throw BusinessException.invalid("Keep a time or distance to 20 characters.");
				}
				marks.put(p.entryId(), mark.isEmpty() ? null : mark);
			}
		}
		Standings.checkPlaces(lanes, places);
		for (var entry : lanes) {
			jdbc.sql("UPDATE event_heat_entries SET place = :place, mark = :mark WHERE heat_id = :heat AND entry_id = :entry")
				.param("place", places.get(entry)).param("mark", marks.get(entry)).param("heat", heatId).param("entry", entry).update();
		}
		jdbc.sql("UPDATE event_heats SET recorded_by = :user, recorded_at = :now WHERE id = :id").param("user", userId).param("now", now())
			.param("id", heatId).update();
		if (!heat.isFinal()) {
			remakeFinal(game);
		}
		settle(game);
		var detail = reader.detail(eventId, userId);
		tell(detail, game.id(), lanes, userId, played -> summary(played, heatView(played, heatId)));
		return detail;
	}

	/** Takes a heat's places back, before the final is run. */
	@Transactional
	public EventViews.Detail clearHeat(UUID eventId, UUID heatId, UUID userId) {
		var heat = heat(eventId, heatId);
		var game = access.requireRunner(eventId, heat.gameId(), userId);
		requireOn(eventId);
		if (!heat.isFinal() && finalRun(game.id())) {
			throw BusinessException.conflict("The final has been run, so the heats stand.");
		}
		jdbc.sql("UPDATE event_heat_entries SET place = NULL, mark = NULL WHERE heat_id = :heat").param("heat", heatId).update();
		jdbc.sql("UPDATE event_heats SET recorded_by = NULL, recorded_at = NULL WHERE id = :id").param("id", heatId).update();
		if (!heat.isFinal()) {
			remakeFinal(game);
		}
		settle(game);
		return reader.detail(eventId, userId);
	}

	/* ------------------------------------------------------------------ */
	/* The event                                                            */
	/* ------------------------------------------------------------------ */

	/** The event is over: sign-ups close, and everyone taking part hears who won it. */
	@Transactional
	public EventViews.Detail finish(UUID eventId, UUID userId) {
		var event = access.requireAdmin(eventId, userId);
		if (!event.open()) {
			throw BusinessException.conflict("This event is already over.");
		}
		jdbc.sql("UPDATE events SET status = 'finished', registration_open = false, updated_at = :now WHERE id = :id")
			.param("now", now()).param("id", eventId).update();
		var detail = reader.detail(eventId, userId);
		var top = detail.table().isEmpty() || detail.table().getFirst().points() == 0 ? null : detail.table().getFirst();
		var winner = top == null ? null : detail.groups().stream().filter(g -> g.id().equals(top.groupId())).map(EventViews.Group::name)
			.findFirst().orElse(null);
		var recipients = jdbc.sql("SELECT user_id FROM event_people WHERE event_id = :event AND user_id IS NOT NULL AND user_id <> :me")
			.param("event", eventId).param("me", userId).query(UUID.class).list();
		if (!recipients.isEmpty()) {
			events.publishEvent(new EventActivity.EventFinished(eventId, event.name(), winner, recipients, userId));
		}
		happened.record("event.finished", "event", eventId, userId, event.organisationId(), null, Map.of());
		return detail;
	}

	/** Back on, after finishing it too soon. */
	@Transactional
	public EventViews.Detail reopen(UUID eventId, UUID userId) {
		var event = access.requireAdmin(eventId, userId);
		if (!"finished".equals(event.status())) {
			throw BusinessException.conflict("Only a finished event can be put back on.");
		}
		jdbc.sql("UPDATE events SET status = 'open', updated_at = :now WHERE id = :id").param("now", now()).param("id", eventId).update();
		return reader.detail(eventId, userId);
	}

	public EventViews.Board board(String token) {
		return reader.board(token);
	}

	/* ------------------------------------------------------------------ */
	/* Knockouts                                                            */
	/* ------------------------------------------------------------------ */

	/**
	 * A knockout's later rounds, worked out again from who has won so far. A tie gets each side as
	 * soon as it's known; a tie nobody can reach yet goes; a third-place match appears once both
	 * semi-finals have losers. A played match is never changed underneath: the corrections that would
	 * do that are refused before getting here.
	 */
	private void rebuild(GameRow game, Settings settings) {
		var matches = matches(game.id());
		var firstRound = (int) matches.stream().filter(m -> !m.thirdPlace() && m.round() == 1).count();
		var rounds = Knockout.rounds(firstRound * 2);
		for (int round = 1; round < rounds; round++) {
			var ties = firstRound >> (round - 1);
			for (int k = 0; k < ties / 2; k++) {
				var a = find(matches, round, 2 * k, false);
				var b = find(matches, round, 2 * k + 1, false);
				var home = a == null ? null : a.winner();
				var away = b == null ? null : b.winner();
				var next = find(matches, round + 1, k, false);
				matches = place(game.id(), matches, next, round + 1, k, false, home, away);
			}
		}
		var third = find(matches, rounds, 0, true);
		if (settings.thirdPlace() && rounds >= 2) {
			var semiA = find(matches, rounds - 1, 0, false);
			var semiB = find(matches, rounds - 1, 1, false);
			var home = semiA == null ? null : semiA.loser();
			var away = semiB == null ? null : semiB.loser();
			place(game.id(), matches, third, rounds, 0, true, home, away);
		}
		else if (third != null && !third.recorded()) {
			jdbc.sql("DELETE FROM event_matches WHERE id = :id").param("id", third.id()).update();
		}
	}

	/** Puts the sides into a tie, making or removing it as needed. Returns the matches as they now are. */
	private List<MatchRow> place(UUID gameId, List<MatchRow> matches, MatchRow next, int round, int slot, boolean thirdPlace, UUID home,
			UUID away) {
		if (next != null && next.recorded()) {
			if (!Objects.equals(next.home(), home) || !Objects.equals(next.away(), away)) {
				throw BusinessException.conflict("That tie has been played already. Take its result back first.");
			}
			return matches;
		}
		if (next == null) {
			if (home != null || away != null) {
				insertMatch(gameId, round, slot, thirdPlace, home, away, null);
				return matches(gameId);
			}
			return matches;
		}
		if (home == null && away == null) {
			jdbc.sql("DELETE FROM event_matches WHERE id = :id").param("id", next.id()).update();
			return matches(gameId);
		}
		if (!Objects.equals(next.home(), home) || !Objects.equals(next.away(), away)) {
			jdbc.sql("UPDATE event_matches SET home_entry_id = :home, away_entry_id = :away WHERE id = :id").param("home", home)
				.param("away", away).param("id", next.id()).update();
			return matches(gameId);
		}
		return matches;
	}

	/**
	 * Refuses a correction that would pull the ground from under a later match: the next round's tie
	 * (or the third-place match, from a semi-final) mustn't have been played. Leagues have no such tie.
	 */
	private void requireCorrectable(GameRow game, MatchRow match) {
		if (!"knockout".equals(game.format()) || match.thirdPlace()) {
			return;
		}
		var matches = matches(game.id());
		var next = find(matches, match.round() + 1, match.slot() / 2, false);
		if (next != null && next.recorded()) {
			throw BusinessException.conflict("The next round has been played, so this result stands.");
		}
		var firstRound = (int) matches.stream().filter(m -> !m.thirdPlace() && m.round() == 1).count();
		var rounds = Knockout.rounds(firstRound * 2);
		var third = find(matches, rounds, 0, true);
		if (match.round() == rounds - 1 && third != null && third.recorded()) {
			throw BusinessException.conflict("The match for third place has been played, so this result stands.");
		}
	}

	/* ------------------------------------------------------------------ */
	/* Placings                                                             */
	/* ------------------------------------------------------------------ */

	/** Once every heat is in, the final, from the best of each; until then, none. */
	private void remakeFinal(GameRow game) {
		var settings = settings(game.id());
		var heats = jdbc.sql("SELECT id, game_id, stage, number, recorded_at IS NOT NULL FROM event_heats WHERE game_id = :game AND stage = 'heat' ORDER BY number")
			.param("game", game.id()).query((rs, n) -> new HeatRow((UUID) rs.getObject(1), (UUID) rs.getObject(2), rs.getString(3), rs.getInt(4),
					rs.getBoolean(5))).list();
		jdbc.sql("DELETE FROM event_heats WHERE game_id = :game AND stage = 'final' AND recorded_at IS NULL").param("game", game.id()).update();
		if (heats.isEmpty() || heats.stream().anyMatch(h -> !h.recorded())) {
			return;
		}
		var placed = new ArrayList<List<Standings.Placed>>();
		for (var heat : heats) {
			placed.add(jdbc.sql("SELECT entry_id, place FROM event_heat_entries WHERE heat_id = :heat").param("heat", heat.id())
				.query((rs, n) -> new Standings.Placed((UUID) rs.getObject(1), rs.getInt(2))).list());
		}
		insertHeat(game.id(), "final", 1, Standings.through(placed, settings.advance()));
	}

	private boolean finalRun(UUID gameId) {
		return jdbc.sql("SELECT count(*) FROM event_heats WHERE game_id = :game AND stage = 'final' AND recorded_at IS NOT NULL")
			.param("game", gameId).query(Integer.class).single() > 0;
	}

	/* ------------------------------------------------------------------ */

	/** Whether a game is decided: its final in (and its third-place match), every league match, or its final run. */
	private void settle(GameRow game) {
		boolean decided;
		switch (game.format()) {
			case "knockout" -> {
				var matches = matches(game.id());
				var firstRound = (int) matches.stream().filter(m -> !m.thirdPlace() && m.round() == 1).count();
				var rounds = Knockout.rounds(firstRound * 2);
				var last = find(matches, rounds, 0, false);
				var third = find(matches, rounds, 0, true);
				// A third-place match that can't have two sides (a semi-final was a bye) doesn't hold it up.
				decided = last != null && last.recorded()
						&& (third == null || third.recorded() || third.home() == null || third.away() == null);
			}
			case "league" -> decided = jdbc.sql("SELECT count(*) FROM event_matches WHERE game_id = :game AND recorded_at IS NULL")
				.param("game", game.id()).query(Integer.class).single() == 0;
			default -> decided = finalRun(game.id());
		}
		jdbc.sql("UPDATE event_games SET status = :status, updated_at = :now WHERE id = :id").param("status", decided ? "finished" : "drawn")
			.param("now", now()).param("id", game.id()).update();
	}

	private void requireOn(UUID eventId) {
		if (!access.event(eventId).open()) {
			throw BusinessException.conflict("This event is over. Put it back on to change its results.");
		}
	}

	private Settings settings(UUID gameId) {
		return jdbc.sql("""
				SELECT best_of, draws_allowed, third_place, heat_size, advance_per_heat, location, starts_at FROM event_games WHERE id = :id
				""").param("id", gameId).query((rs, n) -> new Settings(number(rs.getObject(1)), rs.getBoolean(2), rs.getBoolean(3),
				number(rs.getObject(4)), number(rs.getObject(5)), rs.getString(6),
				rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant())).single();
	}

	private static Integer number(Object value) {
		return value == null ? null : ((Number) value).intValue();
	}

	private MatchRow match(UUID eventId, UUID matchId) {
		return jdbc.sql("""
				SELECT m.id, m.game_id, m.round, m.slot, m.third_place, m.home_entry_id, m.away_entry_id, m.winner_entry_id,
				       m.recorded_at IS NOT NULL
				FROM event_matches m JOIN event_games g ON g.id = m.game_id WHERE m.id = :id AND g.event_id = :event
				""").param("id", matchId).param("event", eventId).query((rs, n) -> row(rs)).optional()
			.orElseThrow(() -> BusinessException.notFound("That match isn’t in this event any more."));
	}

	private HeatRow heat(UUID eventId, UUID heatId) {
		return jdbc.sql("""
				SELECT h.id, h.game_id, h.stage, h.number, h.recorded_at IS NOT NULL
				FROM event_heats h JOIN event_games g ON g.id = h.game_id WHERE h.id = :id AND g.event_id = :event
				""").param("id", heatId).param("event", eventId).query((rs, n) -> new HeatRow((UUID) rs.getObject(1), (UUID) rs.getObject(2),
				rs.getString(3), rs.getInt(4), rs.getBoolean(5))).optional()
			.orElseThrow(() -> BusinessException.notFound("That heat isn’t in this event any more."));
	}

	private List<MatchRow> matches(UUID gameId) {
		return jdbc.sql("""
				SELECT id, game_id, round, slot, third_place, home_entry_id, away_entry_id, winner_entry_id, recorded_at IS NOT NULL
				FROM event_matches WHERE game_id = :game
				""").param("game", gameId).query((rs, n) -> row(rs)).list();
	}

	private static MatchRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new MatchRow((UUID) rs.getObject(1), (UUID) rs.getObject(2), rs.getInt(3), rs.getInt(4), rs.getBoolean(5),
				(UUID) rs.getObject(6), (UUID) rs.getObject(7), (UUID) rs.getObject(8), rs.getBoolean(9));
	}

	private static MatchRow find(List<MatchRow> matches, int round, int slot, boolean thirdPlace) {
		return matches.stream().filter(m -> m.round() == round && m.slot() == slot && m.thirdPlace() == thirdPlace).findFirst().orElse(null);
	}

	private void insertMatch(UUID gameId, int round, int slot, boolean thirdPlace, UUID home, UUID away, UUID winner) {
		jdbc.sql("""
				INSERT INTO event_matches (id, game_id, round, slot, third_place, home_entry_id, away_entry_id, winner_entry_id)
				VALUES (:id, :game, :round, :slot, :third, :home, :away, :winner)
				""").param("id", UUID.randomUUID()).param("game", gameId).param("round", round).param("slot", slot).param("third", thirdPlace)
			.param("home", home).param("away", away).param("winner", winner).update();
	}

	private void insertHeat(UUID gameId, String stage, int number, List<UUID> entries) {
		var id = UUID.randomUUID();
		jdbc.sql("INSERT INTO event_heats (id, game_id, stage, number) VALUES (:id, :game, :stage, :number)").param("id", id)
			.param("game", gameId).param("stage", stage).param("number", number).update();
		for (int i = 0; i < entries.size(); i++) {
			jdbc.sql("INSERT INTO event_heat_entries (heat_id, entry_id, lane) VALUES (:heat, :entry, :lane)").param("heat", id)
				.param("entry", entries.get(i)).param("lane", i + 1).update();
		}
	}

	/* ------------------------------------------------------------------ */
	/* Telling people                                                       */
	/* ------------------------------------------------------------------ */

	/** After a draw: each account holder in it hears who they meet first, and when and where if the coordinator said. */
	private void tellFirstUp(UUID eventId, UUID gameId, UUID actorId) {
		var detail = reader.detail(eventId, actorId);
		var game = detail.games().stream().filter(g -> g.id().equals(gameId)).findFirst().orElseThrow();
		var accounts = accounts(detail, game);
		var firstUp = new ArrayList<EventActivity.FirstUp>();
		var zone = ZoneId.of(detail.event().timezone());
		var when = game.startsAt() == null ? game.location()
				: DateTimeFormatter.ofPattern("h:mm a", Locale.UK).format(game.startsAt().atZone(zone)).toLowerCase(Locale.ROOT)
						+ (game.location() == null ? "" : ", " + game.location());
		for (var entry : game.entries()) {
			var opponent = game.matches().stream().filter(m -> m.recordedAt() == null && (entry.id().equals(m.homeEntryId()) || entry.id().equals(m.awayEntryId())))
				.filter(m -> m.homeEntryId() != null && m.awayEntryId() != null)
				.findFirst().map(m -> EventStandings.nameOf(game, entry.id().equals(m.homeEntryId()) ? m.awayEntryId() : m.homeEntryId()))
				.orElse(null);
			for (var user : accounts.getOrDefault(entry.id(), List.of())) {
				if (!user.equals(actorId)) {
					firstUp.add(new EventActivity.FirstUp(user, opponent, when));
				}
			}
		}
		if (!firstUp.isEmpty()) {
			events.publishEvent(new EventActivity.DrawMade(eventId, detail.event().name(), gameId, game.name(), firstUp, actorId));
		}
	}

	/** After a result: the account holders in the entries it involves. */
	private void tell(EventViews.Detail detail, UUID gameId, List<UUID> entries, UUID actorId,
			java.util.function.Function<EventViews.Game, String> summary) {
		var game = detail.games().stream().filter(g -> g.id().equals(gameId)).findFirst().orElseThrow();
		var accounts = accounts(detail, game);
		var recipients = new LinkedHashSet<UUID>();
		entries.forEach(e -> recipients.addAll(accounts.getOrDefault(e, List.of())));
		recipients.remove(actorId);
		if (!recipients.isEmpty()) {
			events.publishEvent(new EventActivity.ResultRecorded(detail.event().id(), detail.event().name(), gameId, game.name(),
					summary.apply(game), List.copyOf(recipients), actorId));
		}
	}

	/** The accounts in each entry of a game. */
	private Map<UUID, List<UUID>> accounts(EventViews.Detail detail, EventViews.Game game) {
		var userOf = detail.people().stream().filter(p -> p.userId() != null)
			.collect(Collectors.toMap(EventViews.Person::id, EventViews.Person::userId));
		var accounts = new HashMap<UUID, List<UUID>>();
		for (var entry : game.entries()) {
			accounts.put(entry.id(), entry.personIds().stream().map(userOf::get).filter(Objects::nonNull).toList());
		}
		return accounts;
	}

	private static EventViews.Match matchView(EventViews.Game game, UUID matchId) {
		return game.matches().stream().filter(m -> m.id().equals(matchId)).findFirst().orElseThrow();
	}

	private static EventViews.Heat heatView(EventViews.Game game, UUID heatId) {
		return game.heats().stream().filter(h -> h.id().equals(heatId)).findFirst().orElseThrow();
	}

	/** "Joy Fellowship 2–1 Hope Fellowship", "Kofi beat Esi", "Ama and Kojo drew": a match as people say it. */
	static String summary(EventViews.Game game, EventViews.Match m) {
		var home = EventStandings.nameOf(game, m.homeEntryId());
		var away = EventStandings.nameOf(game, m.awayEntryId());
		if ("walkover".equals(m.decidedBy())) {
			var winner = EventStandings.nameOf(game, m.winnerEntryId());
			return "%s went through: %s didn’t play".formatted(winner, winner.equals(home) ? away : home);
		}
		if (m.homeScore() == null) {
			if (m.winnerEntryId() == null) {
				return "%s and %s drew".formatted(home, away);
			}
			var winner = EventStandings.nameOf(game, m.winnerEntryId());
			return "%s beat %s".formatted(winner, winner.equals(home) ? away : home);
		}
		var score = "%s %d–%d %s".formatted(home, m.homeScore(), m.awayScore(), away);
		if ("penalties".equals(m.decidedBy())) {
			score += " (%d–%d on penalties)".formatted(m.homePenalties(), m.awayPenalties());
		}
		return score;
	}

	/** "Heat 2: Kofi 1st, Esi 2nd, Ama 3rd". */
	static String summary(EventViews.Game game, EventViews.Heat h) {
		var order = new TreeMap<Integer, String>();
		h.lanes().stream().filter(l -> l.place() != null).forEach(l -> order.put(l.place(), EventStandings.nameOf(game, l.entryId())));
		var placed = order.entrySet().stream().limit(3).map(e -> e.getValue() + " " + Standings.ordinal(e.getKey()))
			.collect(Collectors.joining(", "));
		return ("final".equals(h.stage()) ? "Final" : "Heat " + h.number()) + ": " + placed;
	}

	private OffsetDateTime now() {
		return clock.instant().atOffset(ZoneOffset.UTC);
	}

}
