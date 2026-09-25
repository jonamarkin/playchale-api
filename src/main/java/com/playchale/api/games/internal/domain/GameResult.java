package com.playchale.api.games.internal.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.playchale.api.catalog.api.SportCatalog;
import com.playchale.api.catalog.api.SportScoring;
import com.playchale.api.shared.error.BusinessException;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.ListIndexBase;
import org.springframework.data.domain.Persistable;

/**
 * A game's result: recorded by the host, then checked by the players. The host's word counts
 * straight away; players who disagree say so, and the host corrects it.
 */
@Entity
@Table(name = "game_results")
public class GameResult implements Persistable<UUID> {

	@Id
	private UUID gameId;

	private int homeScore;

	private int awayScore;

	private UUID recordedBy;

	private Instant recordedAt;

	@ElementCollection
	@CollectionTable(name = "result_players", joinColumns = @JoinColumn(name = "game_id"))
	private List<ResultLine> players = new ArrayList<>();

	@ElementCollection
	@CollectionTable(name = "result_sets", joinColumns = @JoinColumn(name = "game_id"))
	@OrderColumn(name = "number")
	@ListIndexBase(1)
	private List<SetScore> sets = new ArrayList<>();

	@ElementCollection
	@CollectionTable(name = "result_confirmations", joinColumns = @JoinColumn(name = "game_id"))
	private Set<Confirmation> confirmations = new HashSet<>();

	@ElementCollection
	@CollectionTable(name = "result_disputes", joinColumns = @JoinColumn(name = "game_id"))
	private Set<Dispute> disputes = new HashSet<>();

	@Transient
	private boolean isNew = true;

	protected GameResult() {
	}

	/** A first result for a game. */
	public GameResult(Game game, ResultInput input, UUID host, Instant now) {
		this.gameId = game.getId();
		record(game, input, host, now);
	}

	/**
	 * Records (or corrects) the result. It's checked against the sport's rules and the roster, set-based
	 * sports are scored from their sets, and players only keep the stats their sport tracks. A correction
	 * clears everyone's confirmations and disputes: they check the new one.
	 */
	public void record(Game game, ResultInput input, UUID host, Instant now) {
		var rules = SportCatalog.scoring(game.getSport());
		var roster = game.getParticipants().stream().collect(Collectors.toMap(Participant::playerKey, Function.identity()));
		var home = distinct(input.home());
		var away = distinct(input.away());
		if (home.isEmpty() || away.isEmpty()) {
			throw BusinessException.invalid("Put at least one player on each side.");
		}
		if (!roster.keySet().containsAll(home) || !roster.keySet().containsAll(away) || home.stream().anyMatch(away::contains)) {
			throw BusinessException.invalid("Each player can only be on one side.");
		}

		var scorers = scorers(input, rules, roster.keySet());
		this.sets = new ArrayList<>();
		if (rules.sets()) {
			var entered = input.sets() == null ? List.<SetScore>of() : input.sets();
			if (entered.isEmpty()) {
				throw BusinessException.invalid("Add the score of at least one set.");
			}
			if (entered.size() > rules.maxSets()) {
				throw BusinessException.invalid("A match has at most %d sets.".formatted(rules.maxSets()));
			}
			for (int i = 0; i < entered.size(); i++) {
				var set = entered.get(i);
				if (set.home() < 0 || set.away() < 0 || set.home() > rules.max() || set.away() > rules.max()) {
					throw BusinessException.invalid("Set %d’s score isn’t possible. Check it.".formatted(i + 1));
				}
				if (set.home() == set.away()) {
					throw BusinessException.invalid("Set %d can’t end level. Check its score.".formatted(i + 1));
				}
			}
			this.sets.addAll(entered);
			this.homeScore = (int) entered.stream().filter(s -> s.home() > s.away()).count();
			this.awayScore = (int) entered.stream().filter(s -> s.away() > s.home()).count();
			scorers = Map.of();
		}
		else {
			if (input.homeScore() < 0 || input.awayScore() < 0 || input.homeScore() > rules.max() || input.awayScore() > rules.max()) {
				throw BusinessException.invalid("Scores go from 0 to %d.".formatted(rules.max()));
			}
			var key = rules.countsToScore();
			if (key != null) {
				var byScorer = scorers;
				int homeTotal = home.stream().mapToInt(k -> byScorer.containsKey(k) ? byScorer.get(k).stat(key) : 0).sum();
				int awayTotal = away.stream().mapToInt(k -> byScorer.containsKey(k) ? byScorer.get(k).stat(key) : 0).sum();
				if (homeTotal > input.homeScore() || awayTotal > input.awayScore()) {
					throw BusinessException.invalid("The %s add up to more than the score.".formatted(key));
				}
			}
			this.homeScore = input.homeScore();
			this.awayScore = input.awayScore();
		}

		var absent = distinct(input.absent()).stream()
			.filter(k -> roster.containsKey(k) && !home.contains(k) && !away.contains(k))
			.toList();
		var lines = new ArrayList<ResultLine>();
		for (var k : home) {
			lines.add(line(k, roster.get(k), ResultLine.HOME, scorers.get(k)));
		}
		for (var k : away) {
			lines.add(line(k, roster.get(k), ResultLine.AWAY, scorers.get(k)));
		}
		for (var k : absent) {
			lines.add(line(k, roster.get(k), ResultLine.ABSENT, null));
		}
		this.players = lines;
		this.recordedBy = host;
		this.recordedAt = now;
		this.confirmations = new HashSet<>();
		this.disputes = new HashSet<>();
	}

	/** A player who was in the game saying the result is right. */
	public void confirm(UUID userId, Instant now) {
		requireCheckableBy(userId, "You recorded this result.");
		disputes.removeIf(d -> d.userId().equals(userId));
		if (confirmations.stream().noneMatch(c -> c.userId().equals(userId))) {
			confirmations.add(new Confirmation(userId, now));
		}
	}

	/** A player who was in the game saying it isn't right. Returns the reason as kept. */
	public String dispute(UUID userId, String reason, Instant now) {
		requireCheckableBy(userId, "You recorded this result. Correct it instead.");
		var note = reason == null ? "" : reason.strip();
		note = note.substring(0, Math.min(140, note.length()));
		confirmations.removeIf(c -> c.userId().equals(userId));
		disputes.removeIf(d -> d.userId().equals(userId));
		disputes.add(new Dispute(userId, note.isEmpty() ? null : note, now));
		return note.isEmpty() ? null : note;
	}

	/** Only people who actually played can vouch for a result: in the game, and not marked absent. */
	private void requireCheckableBy(UUID userId, String recordedItYourself) {
		if (lineOf(userId).filter(ResultLine::played).isEmpty()) {
			throw BusinessException.conflict("Only players who were in this game can check the result.");
		}
		if (recordedBy.equals(userId)) {
			throw BusinessException.conflict(recordedItYourself);
		}
	}

	/** W, D or L for a player on a side; empty for anyone else. */
	public Optional<String> outcomeFor(UUID userId) {
		return lineOf(userId).filter(ResultLine::played).map(line -> {
			if (homeScore == awayScore) {
				return "D";
			}
			return ResultLine.HOME.equals(line.side()) == homeScore > awayScore ? "W" : "L";
		});
	}

	public Optional<ResultLine> lineOf(UUID userId) {
		return players.stream().filter(l -> userId.equals(l.userId())).findFirst();
	}

	private static ResultLine line(String key, Participant spot, String side, ResultInput.Scorer scorer) {
		return new ResultLine(key, spot.getUserId(), side, scorer == null ? 0 : scorer.stat("goals"),
				scorer == null ? 0 : scorer.stat("assists"), scorer == null ? 0 : scorer.stat("points"));
	}

	/** The sport's own stats for players in the game, and nothing else. */
	private static Map<String, ResultInput.Scorer> scorers(ResultInput input, SportScoring rules, Set<String> roster) {
		var kept = new LinkedHashMap<String, ResultInput.Scorer>();
		for (var s : input.scorers() == null ? List.<ResultInput.Scorer>of() : input.scorers()) {
			if (s == null || !roster.contains(s.userId())) {
				continue;
			}
			for (var key : rules.playerStats()) {
				int value = s.stat(key);
				if (value < 0 || value > SportScoring.STAT_MAX.get(key)) {
					throw BusinessException.invalid("Check the %s: %d is the most one player can have.".formatted(key, SportScoring.STAT_MAX.get(key)));
				}
			}
			var keep = new ResultInput.Scorer(s.userId(), keep(rules, "goals", s), keep(rules, "assists", s), keep(rules, "points", s));
			if (rules.playerStats().stream().anyMatch(k -> keep.stat(k) > 0)) {
				kept.put(s.userId(), keep);
			}
		}
		return kept;
	}

	private static Integer keep(SportScoring rules, String key, ResultInput.Scorer s) {
		return rules.playerStats().contains(key) && s.stat(key) > 0 ? s.stat(key) : null;
	}

	private static List<String> distinct(List<String> keys) {
		return keys == null ? List.of() : List.copyOf(new LinkedHashSet<>(keys));
	}

	public UUID getGameId() {
		return gameId;
	}

	public int getHomeScore() {
		return homeScore;
	}

	public int getAwayScore() {
		return awayScore;
	}

	public UUID getRecordedBy() {
		return recordedBy;
	}

	public Instant getRecordedAt() {
		return recordedAt;
	}

	public List<ResultLine> getPlayers() {
		return List.copyOf(players);
	}

	public List<SetScore> getSets() {
		return List.copyOf(sets);
	}

	public List<Confirmation> getConfirmations() {
		return confirmations.stream().sorted(Comparator.comparing(Confirmation::confirmedAt)).toList();
	}

	public List<Dispute> getDisputes() {
		return disputes.stream().sorted(Comparator.comparing(Dispute::disputedAt)).toList();
	}

	@Override
	public UUID getId() {
		return gameId;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	@PostLoad
	@PostPersist
	void stored() {
		isNew = false;
	}

}
