package com.playchale.api.catalog.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every sport PlayChale supports. Kept in code, like the web app's copy (webapp/app/data/sports.ts),
 * because adding a sport also means adding how it's scored. Keep the two in step: the web app's
 * end-to-end test catalog.spec.ts compares them when run against this API.
 *
 * <p>A new sport is one entry here: its formats, its positions (or events, for athletics: a longer
 * list with {@link RoleOption#group() groups} and a higher {@link SportRoles#max() max}) and how it's
 * scored.
 */
public final class SportCatalog {

	/** Team sports: up to two positions, the first being where they usually play. */
	private static SportRoles positions(RoleOption... options) {
		return new SportRoles("Position", 2, List.of(options));
	}

	private static final List<Sport> SPORTS = List.of(
			new Sport("football", "Football", "ph:soccer-ball-fill", List.of("5-a-side", "7-a-side", "11-a-side"),
					positions(RoleOption.of("goalkeeper", "Goalkeeper"), RoleOption.of("defender", "Defender"),
							RoleOption.of("midfielder", "Midfielder"), RoleOption.of("forward", "Forward"), RoleOption.anywhere())),
			new Sport("basketball", "Basketball", "ph:basketball-fill", List.of("3x3", "5v5"),
					positions(RoleOption.of("guard", "Guard"), RoleOption.of("forward", "Forward"), RoleOption.of("center", "Center"),
							RoleOption.anywhere())),
			new Sport("volleyball", "Volleyball", "ph:volleyball-fill", List.of("6v6", "Beach 2v2"),
					positions(RoleOption.of("setter", "Setter"), RoleOption.of("outside-hitter", "Outside hitter"),
							RoleOption.of("opposite", "Opposite"), RoleOption.of("middle-blocker", "Middle blocker"),
							RoleOption.of("libero", "Libero"), RoleOption.anywhere())),
			// No positions in tennis, so the app doesn't ask.
			new Sport("tennis", "Tennis", "ph:tennis-ball-fill", List.of("Singles", "Doubles"), null));

	private static final Map<String, SportScoring> SCORING = Map.of(
			"football", new SportScoring(false, 99, 0, List.of("goals", "assists"), "goals"),
			"basketball", new SportScoring(false, 199, 0, List.of("points"), "points"),
			"volleyball", new SportScoring(true, 50, 5, List.of(), null),
			"tennis", new SportScoring(true, 7, 5, List.of(), null));

	private SportCatalog() {
	}

	/** How a supported sport is scored. */
	public static SportScoring scoring(String sportId) {
		var scoring = SCORING.get(sportId);
		if (scoring == null) {
			throw new IllegalArgumentException("Not a supported sport: " + sportId);
		}
		return scoring;
	}

	public static List<Sport> all() {
		return SPORTS;
	}

	public static Optional<Sport> find(String id) {
		return SPORTS.stream().filter(s -> s.id().equals(id)).findFirst();
	}

	public static boolean exists(String id) {
		return find(id).isPresent();
	}

}
