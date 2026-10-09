package com.playchale.api.events.internal.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The games a church or school day is made of, ready to add, and how each is played unless the
 * admin says otherwise. The same list as the web app's {@code data/disciplines.ts}.
 *
 * <p>Anything not here is a custom game, where the admin picks every setting themselves.
 */
public final class Disciplines {

	public static final String CUSTOM = "custom";

	public static final List<String> ENTRY_KINDS = List.of("single", "pair", "team");

	public static final List<String> FORMATS = List.of("knockout", "league", "placings");

	/** How a two-sided match is scored. A placings game is always scored by finishing order. */
	public static final List<String> MATCH_SCORING = List.of("score", "sets", "outcome");

	private static final List<String> TWO_SIDED = List.of("knockout", "league");

	/**
	 * One kind of game: who can enter it, how it can be played, and its usual settings. The first
	 * entry kind and the first format are the ones it starts with.
	 *
	 * @param scoring  how its matches are scored; null for a game only ever played for placings
	 * @param draws    whether a league match can end level by default
	 * @param heatSize how many play at once in a heat (a ludo board, a race's lanes), for placings
	 * @param advance  how many from each heat go through to the final, for placings
	 */
	public record Discipline(String key, String name, List<String> entryKinds, List<String> formats, String scoring,
			Integer bestOf, boolean draws, Integer heatSize, Integer advance) {

		public String entryKind() {
			return entryKinds.getFirst();
		}

		public String format() {
			return formats.getFirst();
		}

		/** How a game in this format is scored: a placings game by finishing order, others by the discipline's own way. */
		public String scoringFor(String format) {
			return "placings".equals(format) ? "placings" : scoring;
		}

		public boolean custom() {
			return CUSTOM.equals(key);
		}

	}

	private static final Map<String, Discipline> ALL = new LinkedHashMap<>();

	static {
		team("football", "Football", "score", true);
		team("basketball", "Basketball", "score", false);
		add(new Discipline("volleyball", "Volleyball", List.of("team"), TWO_SIDED, "sets", 3, false, null, null));
		team("handball", "Handball", "score", true);
		team("netball", "Netball", "score", true);
		add(new Discipline("table-tennis", "Table tennis", List.of("single", "pair"), TWO_SIDED, "sets", 3, false, null, null));
		add(new Discipline("badminton", "Badminton", List.of("single", "pair"), TWO_SIDED, "sets", 3, false, null, null));
		add(new Discipline("tennis", "Tennis", List.of("single", "pair"), TWO_SIDED, "sets", 3, false, null, null));
		team("tug-of-war", "Tug of war", "outcome", false);
		add(new Discipline("ampe", "Ampe", List.of("team", "single"), TWO_SIDED, "score", null, true, null, null));
		board("draughts", "Draughts");
		board("chess", "Chess");
		board("oware", "Oware");
		add(new Discipline("ludo", "Ludo", List.of("single", "pair"), List.of("placings"), null, null, false, 4, 1));
		add(new Discipline("scrabble", "Scrabble", List.of("single", "pair"), TWO_SIDED, "score", null, false, null, null));
		add(new Discipline("race", "Race", List.of("single"), List.of("placings"), null, null, false, 8, 2));
		add(new Discipline("relay", "Relay", List.of("team"), List.of("placings"), null, null, false, 6, 2));
		add(new Discipline("quiz", "Quiz", List.of("team", "single"), List.of("placings", "knockout", "league"), "score", null, false, 4, 1));
		add(new Discipline(CUSTOM, "Custom game", ENTRY_KINDS, FORMATS, "score", null, false, 4, 1));
	}

	private static void team(String key, String name, String scoring, boolean draws) {
		add(new Discipline(key, name, List.of("team"), TWO_SIDED, scoring, null, draws, null, null));
	}

	/** A board game for two: won, lost or drawn. */
	private static void board(String key, String name) {
		add(new Discipline(key, name, List.of("single"), TWO_SIDED, "outcome", null, true, null, null));
	}

	private static void add(Discipline discipline) {
		ALL.put(discipline.key(), discipline);
	}

	private Disciplines() {
	}

	public static Optional<Discipline> find(String key) {
		return Optional.ofNullable(key == null ? null : ALL.get(key));
	}

	public static List<Discipline> all() {
		return List.copyOf(ALL.values());
	}

}
