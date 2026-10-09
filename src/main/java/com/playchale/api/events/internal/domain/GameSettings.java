package com.playchale.api.events.internal.domain;

import java.time.Instant;
import java.util.List;

import com.playchale.api.events.internal.domain.Disciplines.Discipline;
import com.playchale.api.shared.error.BusinessException;

/**
 * How one game in an event is played, made whole from what the admin asked for: anything they left
 * out comes from the discipline, and anything that makes no sense for it is refused, so a game can
 * never be set up in a way its draw or its results can't handle.
 */
public record GameSettings(String discipline, String name, String category, String entryKind, Integer teamSize, String format,
		String scoring, Integer bestOf, boolean drawsAllowed, boolean thirdPlace, Integer heatSize, Integer advancePerHeat,
		String location, Instant startsAt) {

	private static final List<Integer> BEST_OF = List.of(1, 3, 5);

	/** What the admin asked for. Any of it may be left out. */
	public record Asked(String discipline, String name, String category, String entryKind, Integer teamSize, String format,
			String scoring, Integer bestOf, Boolean drawsAllowed, Boolean thirdPlace, Integer heatSize, Integer advancePerHeat,
			String location, Instant startsAt) {
	}

	public static GameSettings of(Asked asked) {
		var discipline = Disciplines.find(asked.discipline())
			.orElseThrow(() -> BusinessException.invalid("Pick a game from the list, or make your own."));
		var name = text(asked.name(), 60);
		if (name == null) {
			if (discipline.custom()) {
				throw BusinessException.invalid("Give your game a name.");
			}
			name = discipline.name();
		}
		var category = text(asked.category(), 40);
		var entryKind = choose(asked.entryKind(), discipline.entryKinds(), "%s can’t be played that way. Pick singles, pairs or teams it allows."
			.formatted(discipline.name()));
		var format = choose(asked.format(), discipline.formats(), "%s can’t be run that way.".formatted(discipline.name()));
		var scoring = scoring(discipline, format, asked.scoring());

		Integer bestOf = null;
		if ("sets".equals(scoring)) {
			bestOf = asked.bestOf() != null ? asked.bestOf() : discipline.bestOf() != null ? discipline.bestOf() : 3;
			if (!BEST_OF.contains(bestOf)) {
				throw BusinessException.invalid("Play best of 1, 3 or 5 sets.");
			}
		}
		// Only a league can end level: a knockout needs someone to go through.
		var draws = "league".equals(format) && !"sets".equals(scoring)
				&& (asked.drawsAllowed() != null ? asked.drawsAllowed() : discipline.draws());
		var thirdPlace = "knockout".equals(format) && Boolean.TRUE.equals(asked.thirdPlace());

		Integer heatSize = null;
		Integer advance = null;
		if ("placings".equals(format)) {
			heatSize = asked.heatSize() != null ? asked.heatSize() : discipline.heatSize() != null ? discipline.heatSize() : 4;
			if (heatSize < 2 || heatSize > 20) {
				throw BusinessException.invalid("A heat holds between 2 and 20.");
			}
			advance = asked.advancePerHeat() != null ? asked.advancePerHeat() : discipline.advance() != null ? discipline.advance() : 1;
			if (advance < 1 || advance >= heatSize) {
				throw BusinessException.invalid("Fewer go through from a heat than are in it.");
			}
		}

		Integer teamSize = null;
		if ("team".equals(entryKind) && asked.teamSize() != null) {
			teamSize = asked.teamSize();
			if (teamSize < 2 || teamSize > 60) {
				throw BusinessException.invalid("A team has between 2 and 60 players.");
			}
		}
		else if ("pair".equals(entryKind)) {
			teamSize = 2;
		}
		return new GameSettings(discipline.key(), name, category, entryKind, teamSize, format, scoring, bestOf, draws, thirdPlace,
				heatSize, advance, text(asked.location(), 60), asked.startsAt());
	}

	/** A ready-made game is scored its own way; a custom one the way the admin picked. */
	private static String scoring(Discipline discipline, String format, String asked) {
		if ("placings".equals(format)) {
			return "placings";
		}
		if (!discipline.custom()) {
			return discipline.scoringFor(format);
		}
		var scoring = asked == null || asked.isBlank() ? "score" : asked.strip();
		if (!Disciplines.MATCH_SCORING.contains(scoring)) {
			throw BusinessException.invalid("Score it by points, by sets, or by who won.");
		}
		return scoring;
	}

	private static String choose(String asked, List<String> allowed, String message) {
		if (asked == null || asked.isBlank()) {
			return allowed.getFirst();
		}
		if (!allowed.contains(asked.strip())) {
			throw BusinessException.invalid(message);
		}
		return asked.strip();
	}

	/** Trimmed, with runs of spaces made one; null if there's nothing. Refused if too long. */
	public static String text(String value, int max) {
		if (value == null) {
			return null;
		}
		var clean = value.strip().replaceAll("\\s+", " ");
		if (clean.isEmpty()) {
			return null;
		}
		if (clean.length() > max) {
			throw BusinessException.invalid("Keep it to %d characters.".formatted(max));
		}
		return clean;
	}

	public boolean twoSided() {
		return !"placings".equals(format);
	}

}
