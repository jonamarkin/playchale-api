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
import java.util.Comparator;
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
import com.playchale.api.events.internal.domain.GameSettings;
import com.playchale.api.events.internal.domain.Pools;
import com.playchale.api.events.internal.domain.Standings;
import com.playchale.api.events.internal.domain.Timetable;
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
 * Playing an event's games: the draw, when and where each match is played, the results, and
 * finishing the event.
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

	/** A game's plan for the day: when the first match starts, how long each takes, and where they're played at once. */
	public record Plan(Instant startsAt, Integer minutes, List<String> locations) {
	}

	/** One match or heat moved by hand. Either may be left out, to clear it. */
	public record Slot(Instant startsAt, String location) {
	}

	private static final int MAX_LOCATIONS = 12;

	/** What a game is played with, beyond what the rules turn on. */
	private record Settings(Integer bestOf, boolean drawsAllowed, boolean thirdPlace, Integer heatSize, Integer advance, String location,
			Instant startsAt, Integer poolSize, Integer advancePerPool) {
	}

	/** A match; {@code pool} set for a pool match, null for a knockout's or a league's. */
	private record MatchRow(UUID id, UUID gameId, int round, int slot, boolean thirdPlace, UUID home, UUID away, UUID winner,
			boolean recorded, Integer pool) {

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
			case "pools" -> {
				var pools = Pools.deal(order, settings.poolSize());
				if (pools.stream().anyMatch(p -> p.size() < 2)) {
					throw BusinessException.invalid("Pools of %d would leave someone in a pool on their own. Make the pools smaller."
						.formatted(settings.poolSize()));
				}
				if (pools.stream().mapToInt(p -> Math.min(p.size(), settings.advancePerPool())).sum() < 2) {
					throw BusinessException.invalid("Only one would go through to the knockout. Let more through, or make the pools smaller.");
				}
				for (int p = 0; p < pools.size(); p++) {
					for (var entry : pools.get(p)) {
						jdbc.sql("UPDATE event_entries SET pool = :pool WHERE id = :id").param("pool", p + 1).param("id", entry).update();
					}
					var rounds = RoundRobin.rounds(pools.get(p));
					for (int r = 0; r < rounds.size(); r++) {
						for (int s = 0; s < rounds.get(r).size(); s++) {
							var pairing = rounds.get(r).get(s);
							insertMatch(gameId, p + 1, r + 1, s, false, pairing.home(), pairing.away(), null);
						}
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
		fill(gameId, false);
		var event = access.event(eventId);
		happened.record("event.game-drawn", "event", eventId, userId, event.organisationId(), null,
				Map.of("format", game.format(), "entries", entries.size()));
		tellFirstUp(eventId, gameId, userId, "draw", null);
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
		jdbc.sql("UPDATE event_entries SET pool = NULL WHERE game_id = :game").param("game", gameId).update();
		jdbc.sql("UPDATE event_games SET status = 'open', updated_at = :now WHERE id = :id").param("now", now()).param("id", gameId).update();
		return reader.detail(eventId, userId);
	}

	/* ------------------------------------------------------------------ */
	/* When and where                                                       */
	/* ------------------------------------------------------------------ */

	/**
	 * Plans a game's day: every match and heat not yet played gets a time and a place, and the ones
	 * made later (the next round, a final) get theirs as they appear. Saved again, it plans again,
	 * over any match moved by hand. After the draw, everyone in it with an account hears when and
	 * where they're first up.
	 */
	@Transactional
	public EventViews.Detail plan(UUID eventId, UUID gameId, Plan plan, UUID userId) {
		var game = access.requireRunner(eventId, gameId, userId);
		requireOn(eventId);
		if (plan == null || plan.startsAt() == null) {
			throw BusinessException.invalid("Say when the first one starts.");
		}
		if (plan.minutes() == null || plan.minutes() < 5 || plan.minutes() > 600) {
			throw BusinessException.invalid("Each one takes between 5 minutes and 10 hours.");
		}
		var locations = new ArrayList<String>();
		for (var location : plan.locations() == null ? List.<String>of() : plan.locations()) {
			var clean = GameSettings.text(location, 60);
			if (clean != null && locations.stream().noneMatch(l -> l.equalsIgnoreCase(clean))) {
				locations.add(clean);
			}
		}
		if (locations.isEmpty()) {
			throw BusinessException.invalid("Say where it’s played: a court, a table, a pitch.");
		}
		if (locations.size() > MAX_LOCATIONS) {
			throw BusinessException.invalid("Up to %d places at once.".formatted(MAX_LOCATIONS));
		}
		var together = String.join(", ", locations);
		jdbc.sql("""
				UPDATE event_games SET starts_at = :startsAt, match_minutes = :minutes, locations = :locations, location = :location,
				       updated_at = :now
				WHERE id = :id
				""").param("startsAt", plan.startsAt().atOffset(ZoneOffset.UTC)).param("minutes", plan.minutes())
			.param("locations", locations.toArray(String[]::new)).param("location", together.length() <= 60 ? together : locations.getFirst())
			.param("now", now()).param("id", gameId).update();
		fill(gameId, true);
		happened.record("event.game-planned", "event", eventId, userId, access.event(eventId).organisationId(), null,
				Map.of("minutes", plan.minutes(), "locations", locations.size()));
		if (!game.open()) {
			tellFirstUp(eventId, gameId, userId, "times", null);
		}
		return reader.detail(eventId, userId);
	}

	/** Moves one match: another time, another court. Both sides hear, if they have an account. */
	@Transactional
	public EventViews.Detail moveMatch(UUID eventId, UUID matchId, Slot slot, UUID userId) {
		var match = match(eventId, matchId);
		var game = access.requireRunner(eventId, match.gameId(), userId);
		requireOn(eventId);
		var location = GameSettings.text(slot == null ? null : slot.location(), 60);
		var startsAt = slot == null ? null : slot.startsAt();
		var before = jdbc.sql("SELECT starts_at, location FROM event_matches WHERE id = :id").param("id", matchId)
			.query((rs, n) -> new Slot(rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), rs.getString(2))).single();
		jdbc.sql("UPDATE event_matches SET starts_at = :startsAt, location = :location WHERE id = :id")
			.param("startsAt", startsAt == null ? null : startsAt.atOffset(ZoneOffset.UTC)).param("location", location).param("id", matchId)
			.update();
		var detail = reader.detail(eventId, userId);
		if (!match.recorded() && match.home() != null && match.away() != null && !before.equals(new Slot(startsAt, location))) {
			tell(detail, game.id(), List.of(match.home(), match.away()), userId, played -> {
				var m = matchView(played, matchId);
				return "Moved: %s v %s%s.".formatted(EventStandings.nameOf(played, m.homeEntryId()), EventStandings.nameOf(played, m.awayEntryId()),
						at(detail, m.startsAt(), m.location(), " is now "));
			});
		}
		return detail;
	}

	/** Moves one heat, or a final. Everyone in it hears, if they have an account. */
	@Transactional
	public EventViews.Detail moveHeat(UUID eventId, UUID heatId, Slot slot, UUID userId) {
		var heat = heat(eventId, heatId);
		var game = access.requireRunner(eventId, heat.gameId(), userId);
		requireOn(eventId);
		var location = GameSettings.text(slot == null ? null : slot.location(), 60);
		var startsAt = slot == null ? null : slot.startsAt();
		var before = jdbc.sql("SELECT starts_at, location FROM event_heats WHERE id = :id").param("id", heatId)
			.query((rs, n) -> new Slot(rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), rs.getString(2))).single();
		jdbc.sql("UPDATE event_heats SET starts_at = :startsAt, location = :location WHERE id = :id")
			.param("startsAt", startsAt == null ? null : startsAt.atOffset(ZoneOffset.UTC)).param("location", location).param("id", heatId)
			.update();
		var detail = reader.detail(eventId, userId);
		if (!heat.recorded() && !before.equals(new Slot(startsAt, location))) {
			var lanes = jdbc.sql("SELECT entry_id FROM event_heat_entries WHERE heat_id = :heat").param("heat", heatId).query(UUID.class).list();
			tell(detail, game.id(), lanes, userId, played -> {
				var h = heatView(played, heatId);
				return "Moved: %s%s.".formatted(heat.isFinal() ? "the final" : "Heat " + heat.number(), at(detail, h.startsAt(), h.location(), " is now "));
			});
		}
		return detail;
	}

	/**
	 * Gives a game's matches and heats their time and place from its plan, if it has one: everything
	 * not yet played when {@code all} (the plan was just saved), otherwise only what has no time yet
	 * (a round or a final just made), so a match moved by hand stays where it was put.
	 */
	private void fill(UUID gameId, boolean all) {
		record Planned(Instant startsAt, Integer minutes, String[] locations, String format, boolean thirdPlace) {
		}
		var planned = jdbc.sql("SELECT starts_at, match_minutes, locations, format, third_place FROM event_games WHERE id = :id")
			.param("id", gameId)
			.query((rs, n) -> new Planned(rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), number(rs.getObject(2)),
					rs.getArray(3) == null ? null : (String[]) rs.getArray(3).getArray(), rs.getString(4), rs.getBoolean(5)))
			.single();
		if (planned.startsAt() == null || planned.minutes() == null || planned.locations() == null || planned.locations().length == 0) {
			return;
		}
		var plan = new Timetable.Plan(planned.startsAt(), planned.minutes(), List.of(planned.locations()));
		if ("pools".equals(planned.format())) {
			fillPools(gameId, plan, planned.thirdPlace(), all);
			return;
		}
		if (!"placings".equals(planned.format())) {
			fillMatches(gameId, plan, "knockout".equals(planned.format()), planned.thirdPlace(), all);
			return;
		}
		record TimedHeat(UUID id, boolean isFinal, int number, boolean recorded, boolean timeless) {
		}
		var heats = jdbc.sql("""
				SELECT id, stage = 'final', number, recorded_at IS NOT NULL, starts_at IS NULL FROM event_heats WHERE game_id = :game
				""").param("game", gameId).query((rs, n) -> new TimedHeat((UUID) rs.getObject(1), rs.getBoolean(2), rs.getInt(3), rs.getBoolean(4),
					rs.getBoolean(5))).list();
		var waves = Timetable.heatWaves((int) heats.stream().filter(h -> !h.isFinal()).count());
		for (var h : heats) {
			if (h.recorded() || !(all || h.timeless())) {
				continue;
			}
			var slot = h.isFinal() ? Timetable.slot(plan, waves, waves.size() - 1, 0) : Timetable.slot(plan, waves, 0, h.number() - 1);
			jdbc.sql("UPDATE event_heats SET starts_at = :at, location = :location WHERE id = :id")
				.param("at", slot.startsAt().atOffset(ZoneOffset.UTC)).param("location", slot.location()).param("id", h.id()).update();
		}
	}

	/**
	 * A game in pools: each round of the pools is a wave (every pool's matches of that round), then
	 * the knockout's rounds, shaped by how many will come through before anyone has.
	 */
	private void fillPools(UUID gameId, Timetable.Plan plan, boolean thirdPlace, boolean all) {
		record Timed(UUID id, Integer pool, int round, int slot, boolean thirdPlace, UUID away, boolean recorded, boolean timeless) {
		}
		var matches = jdbc.sql("""
				SELECT id, pool, round, slot, third_place, away_entry_id, recorded_at IS NOT NULL, starts_at IS NULL
				FROM event_matches WHERE game_id = :game ORDER BY pool NULLS LAST, round, slot
				""").param("game", gameId).query((rs, n) -> new Timed((UUID) rs.getObject(1), number(rs.getObject(2)), rs.getInt(3), rs.getInt(4),
					rs.getBoolean(5), (UUID) rs.getObject(6), rs.getBoolean(7), rs.getBoolean(8))).list();
		var pooled = matches.stream().filter(m -> m.pool() != null).toList();
		var poolRounds = pooled.stream().mapToInt(Timed::round).max().orElse(0);
		var waves = new ArrayList<Integer>();
		for (int round = 1; round <= poolRounds; round++) {
			var r = round;
			waves.add((int) pooled.stream().filter(m -> m.round() == r).count());
		}
		var knockout = matches.stream().filter(m -> m.pool() == null).toList();
		var firstRound = knockout.stream().filter(m -> m.round() == 1 && !m.thirdPlace()).toList();
		var ties = firstRound.stream().filter(m -> m.away() != null).map(Timed::id).toList();
		int slots;
		int tieCount;
		if (!firstRound.isEmpty()) {
			slots = firstRound.size();
			tieCount = ties.size();
		}
		else {
			// Not made yet: as many as will come through, from each pool's size.
			var advance = jdbc.sql("SELECT advance_per_pool FROM event_games WHERE id = :id").param("id", gameId).query(Integer.class).single();
			var through = jdbc.sql("SELECT count(*) FROM event_entries WHERE game_id = :game AND status = 'entered' AND pool IS NOT NULL GROUP BY pool")
				.param("game", gameId).query(Integer.class).list().stream().mapToInt(size -> Math.min(size, advance)).sum();
			slots = through < 2 ? 0 : Knockout.size(through) / 2;
			tieCount = through < 2 ? 0 : through - slots;
		}
		var rounds = Knockout.rounds(slots * 2);
		if (slots > 0) {
			waves.addAll(Timetable.knockoutWaves(slots, tieCount, thirdPlace));
		}
		var withThird = thirdPlace && rounds >= 2;
		for (var m : matches) {
			var bye = m.pool() == null && m.round() == 1 && m.away() == null;
			if (bye || m.recorded() || !(all || m.timeless())) {
				continue;
			}
			Timetable.Slot slot;
			if (m.pool() != null) {
				var sameRound = pooled.stream().filter(x -> x.round() == m.round()).map(Timed::id).toList();
				slot = Timetable.slot(plan, waves, m.round() - 1, sameRound.indexOf(m.id()));
			}
			else {
				var index = Timetable.knockoutIndex(m.round(), m.slot(), m.thirdPlace(), rounds, withThird, ties.indexOf(m.id()));
				slot = Timetable.slot(plan, waves, poolRounds + m.round() - 1, index);
			}
			jdbc.sql("UPDATE event_matches SET starts_at = :at, location = :location WHERE id = :id")
				.param("at", slot.startsAt().atOffset(ZoneOffset.UTC)).param("location", slot.location()).param("id", m.id()).update();
		}
	}

	private void fillMatches(UUID gameId, Timetable.Plan plan, boolean knockout, boolean thirdPlace, boolean all) {
		record Timed(UUID id, int round, int slot, boolean thirdPlace, UUID away, boolean recorded, boolean timeless) {
		}
		var matches = jdbc.sql("""
				SELECT id, round, slot, third_place, away_entry_id, recorded_at IS NOT NULL, starts_at IS NULL
				FROM event_matches WHERE game_id = :game AND pool IS NULL ORDER BY third_place, round, slot
				""").param("game", gameId).query((rs, n) -> new Timed((UUID) rs.getObject(1), rs.getInt(2), rs.getInt(3), rs.getBoolean(4),
					(UUID) rs.getObject(5), rs.getBoolean(6), rs.getBoolean(7))).list();
		var firstRound = matches.stream().filter(m -> m.round() == 1 && !m.thirdPlace()).toList();
		// A bye in a knockout's first round isn't played.
		var ties = firstRound.stream().filter(m -> m.away() != null).map(Timed::id).toList();
		var rounds = Knockout.rounds(firstRound.size() * 2);
		var withThird = thirdPlace && rounds >= 2;
		List<Integer> waves;
		if (knockout) {
			waves = Timetable.knockoutWaves(firstRound.size(), ties.size(), thirdPlace);
		}
		else {
			var perRound = new TreeMap<Integer, Integer>();
			matches.forEach(m -> perRound.merge(m.round(), 1, Integer::sum));
			waves = new ArrayList<>();
			for (int round = 1; round <= (perRound.isEmpty() ? 0 : perRound.lastKey()); round++) {
				waves.add(perRound.getOrDefault(round, 0));
			}
		}
		for (var m : matches) {
			var bye = knockout && m.round() == 1 && m.away() == null;
			if (bye || m.recorded() || !(all || m.timeless())) {
				continue;
			}
			var index = knockout ? Timetable.knockoutIndex(m.round(), m.slot(), m.thirdPlace(), rounds, withThird, ties.indexOf(m.id())) : m.slot();
			var slot = Timetable.slot(plan, waves, m.round() - 1, index);
			jdbc.sql("UPDATE event_matches SET starts_at = :at, location = :location WHERE id = :id")
				.param("at", slot.startsAt().atOffset(ZoneOffset.UTC)).param("location", slot.location()).param("id", m.id()).update();
		}
	}

	/**
	 * When and where, as people say it: "10:30 am, Court 2", after {@code lead}; with the day when the
	 * event runs over several. Empty when neither is known.
	 */
	private static String at(EventViews.Detail detail, Instant startsAt, String location, String lead) {
		var parts = new ArrayList<String>();
		if (startsAt != null) {
			var zone = ZoneId.of(detail.event().timezone());
			var oneDay = detail.event().startsOn().equals(detail.event().endsOn());
			var clock = DateTimeFormatter.ofPattern(oneDay ? "h:mm a" : "EEE h:mm a", Locale.UK).format(startsAt.atZone(zone));
			parts.add(clock.replace("AM", "am").replace("PM", "pm"));
		}
		if (location != null) {
			parts.add(location);
		}
		return parts.isEmpty() ? "" : lead + String.join(", ", parts);
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
		var pooled = match.pool() != null;
		if (pooled) {
			requirePoolsOpen(game);
		}
		else if (match.recorded()) {
			requireCorrectable(game, match);
		}
		// A knockout's match (a game in pools has one after them) needs someone to go through.
		var knockout = !pooled && ("knockout".equals(game.format()) || "pools".equals(game.format()));
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
		if (pooled) {
			remakeKnockout(game, settings, userId);
		}
		else if (knockout) {
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
		var pooled = match.pool() != null;
		if (pooled) {
			requirePoolsOpen(game);
		}
		else {
			requireCorrectable(game, match);
		}
		jdbc.sql("""
				UPDATE event_matches SET home_score = NULL, away_score = NULL, winner_entry_id = NULL, decided_by = NULL,
				       home_penalties = NULL, away_penalties = NULL, recorded_by = NULL, recorded_at = NULL
				WHERE id = :id
				""").param("id", matchId).update();
		jdbc.sql("DELETE FROM event_match_sets WHERE match_id = :id").param("id", matchId).update();
		if (pooled) {
			remakeKnockout(game, settings(game.id()), userId);
		}
		else if ("knockout".equals(game.format()) || "pools".equals(game.format())) {
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
		fill(game.id(), false);
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
		if (!("knockout".equals(game.format()) || "pools".equals(game.format())) || match.thirdPlace()) {
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
	/* Pools                                                                */
	/* ------------------------------------------------------------------ */

	/** A pool's results stand once its knockout has started: they decided who's in it. */
	private void requirePoolsOpen(GameRow game) {
		var started = jdbc.sql("SELECT count(*) FROM event_matches WHERE game_id = :game AND pool IS NULL AND recorded_at IS NOT NULL")
			.param("game", game.id()).query(Integer.class).single();
		if (started > 0) {
			throw BusinessException.conflict("The knockout has started, so the pool results stand.");
		}
	}

	/**
	 * The knockout after the pools, made again from the pool tables whenever a pool result changes:
	 * none until every pool match is in, then the best of each pool, a winner against another pool's
	 * runner-up. Whoever's in it with an account hears who they meet, unless it came out the same.
	 */
	private void remakeKnockout(GameRow game, Settings settings, UUID actorId) {
		var before = jdbc.sql("""
				SELECT home_entry_id::text || '/' || coalesce(away_entry_id::text, '') FROM event_matches
				WHERE game_id = :game AND pool IS NULL AND round = 1 AND NOT third_place
				""").param("game", game.id()).query(String.class).set();
		jdbc.sql("DELETE FROM event_matches WHERE game_id = :game AND pool IS NULL").param("game", game.id()).update();
		var open = jdbc.sql("SELECT count(*) FROM event_matches WHERE game_id = :game AND pool IS NOT NULL AND recorded_at IS NULL")
			.param("game", game.id()).query(Integer.class).single();
		if (open > 0) {
			return;
		}
		var view = reader.detail(game.eventId(), actorId).games().stream().filter(g -> g.id().equals(game.id())).findFirst().orElseThrow();
		var tables = view.pools().stream().map(p -> p.table().stream().map(EventViews.LeagueRow::entryId).toList()).toList();
		var through = Pools.through(tables, settings.advancePerPool());
		var poolOf = new HashMap<UUID, Integer>();
		view.entries().stream().filter(e -> e.pool() != null).forEach(e -> poolOf.put(e.id(), e.pool()));
		var draw = Pools.bracket(through, poolOf);
		draw.ties().forEach(t -> insertMatch(game.id(), 1, t.slot(), false, t.home(), t.away(), null));
		draw.byes().forEach((slot, entry) -> insertMatch(game.id(), 1, slot, false, entry, null, entry));
		rebuild(game, settings);
		var after = jdbc.sql("""
				SELECT home_entry_id::text || '/' || coalesce(away_entry_id::text, '') FROM event_matches
				WHERE game_id = :game AND pool IS NULL AND round = 1 AND NOT third_place
				""").param("game", game.id()).query(String.class).set();
		if (!after.equals(before)) {
			tellFirstUp(game.eventId(), game.id(), actorId, "knockout", java.util.Set.copyOf(through));
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
		fill(game.id(), false);
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
			case "knockout", "pools" -> {
				// matches() is the knockout's alone: a game in pools is decided by the knockout after them.
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
				SELECT best_of, draws_allowed, third_place, heat_size, advance_per_heat, location, starts_at, pool_size, advance_per_pool
				FROM event_games WHERE id = :id
				""").param("id", gameId).query((rs, n) -> new Settings(number(rs.getObject(1)), rs.getBoolean(2), rs.getBoolean(3),
				number(rs.getObject(4)), number(rs.getObject(5)), rs.getString(6),
				rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant(), number(rs.getObject(8)), number(rs.getObject(9)))).single();
	}

	private static Integer number(Object value) {
		return value == null ? null : ((Number) value).intValue();
	}

	private MatchRow match(UUID eventId, UUID matchId) {
		return jdbc.sql("""
				SELECT m.id, m.game_id, m.round, m.slot, m.third_place, m.home_entry_id, m.away_entry_id, m.winner_entry_id,
				       m.recorded_at IS NOT NULL, m.pool
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

	/** A knockout's matches (or a league's): never a pool's, so a game in pools sees only the knockout after them. */
	private List<MatchRow> matches(UUID gameId) {
		return jdbc.sql("""
				SELECT id, game_id, round, slot, third_place, home_entry_id, away_entry_id, winner_entry_id, recorded_at IS NOT NULL, pool
				FROM event_matches WHERE game_id = :game AND pool IS NULL
				""").param("game", gameId).query((rs, n) -> row(rs)).list();
	}

	private static MatchRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new MatchRow((UUID) rs.getObject(1), (UUID) rs.getObject(2), rs.getInt(3), rs.getInt(4), rs.getBoolean(5),
				(UUID) rs.getObject(6), (UUID) rs.getObject(7), (UUID) rs.getObject(8), rs.getBoolean(9), number(rs.getObject(10)));
	}

	private static MatchRow find(List<MatchRow> matches, int round, int slot, boolean thirdPlace) {
		return matches.stream().filter(m -> m.round() == round && m.slot() == slot && m.thirdPlace() == thirdPlace).findFirst().orElse(null);
	}

	private void insertMatch(UUID gameId, int round, int slot, boolean thirdPlace, UUID home, UUID away, UUID winner) {
		insertMatch(gameId, null, round, slot, thirdPlace, home, away, winner);
	}

	private void insertMatch(UUID gameId, Integer pool, int round, int slot, boolean thirdPlace, UUID home, UUID away, UUID winner) {
		jdbc.sql("""
				INSERT INTO event_matches (id, game_id, pool, round, slot, third_place, home_entry_id, away_entry_id, winner_entry_id)
				VALUES (:id, :game, :pool, :round, :slot, :third, :home, :away, :winner)
				""").param("id", UUID.randomUUID()).param("game", gameId).param("pool", pool).param("round", round).param("slot", slot)
			.param("third", thirdPlace).param("home", home).param("away", away).param("winner", winner).update();
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

	/**
	 * After a draw, the times being planned, or a knockout made from the pools ({@code what}): each
	 * account holder in it (or in {@code only}) hears who they meet first (or which heat they're in),
	 * and when and where, as far as that's known.
	 */
	private void tellFirstUp(UUID eventId, UUID gameId, UUID actorId, String what, java.util.Set<UUID> only) {
		var detail = reader.detail(eventId, actorId);
		var game = detail.games().stream().filter(g -> g.id().equals(gameId)).findFirst().orElseThrow();
		var accounts = accounts(detail, game);
		var firstUp = new ArrayList<EventActivity.FirstUp>();
		for (var entry : game.entries()) {
			if (only != null && !only.contains(entry.id())) {
				continue;
			}
			String opponent = null;
			String when = null;
			var match = game.matches().stream()
				.filter(m -> m.recordedAt() == null && m.homeEntryId() != null && m.awayEntryId() != null)
				.filter(m -> entry.id().equals(m.homeEntryId()) || entry.id().equals(m.awayEntryId()))
				.min(Comparator.comparing(EventViews.Match::startsAt, Comparator.nullsLast(Comparator.naturalOrder())));
			var heat = game.heats().stream().filter(h -> h.recordedAt() == null && h.lanes().stream().anyMatch(l -> l.entryId().equals(entry.id())))
				.findFirst();
			if (match.isPresent()) {
				var m = match.get();
				opponent = EventStandings.nameOf(game, entry.id().equals(m.homeEntryId()) ? m.awayEntryId() : m.homeEntryId());
				var at = at(detail, m.startsAt() == null ? game.startsAt() : m.startsAt(), m.location() == null ? game.location() : m.location(), "");
				when = at.isEmpty() ? null : at;
			}
			else if (heat.isPresent()) {
				var h = heat.get();
				when = ("final".equals(h.stage()) ? "the final" : "Heat " + h.number())
						+ at(detail, h.startsAt() == null ? game.startsAt() : h.startsAt(), h.location() == null ? game.location() : h.location(), ", ");
			}
			for (var user : accounts.getOrDefault(entry.id(), List.of())) {
				if (!user.equals(actorId)) {
					firstUp.add(new EventActivity.FirstUp(user, opponent, when));
				}
			}
		}
		if (!firstUp.isEmpty()) {
			events.publishEvent(new EventActivity.DrawMade(eventId, detail.event().name(), gameId, game.name(), firstUp, actorId, what));
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
