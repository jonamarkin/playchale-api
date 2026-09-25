package com.playchale.api.catalog.api;

import java.util.List;
import java.util.Optional;

/**
 * Every sport PlayChale supports. Kept in code, like the web app's copy (webapp/app/data/sports.ts),
 * because adding a sport also means adding how it's scored. Keep the two in step.
 */
public final class SportCatalog {

	private static final List<Sport> SPORTS = List.of(
			new Sport("football", "Football", "ph:soccer-ball-fill", List.of("5-a-side", "7-a-side", "11-a-side")),
			new Sport("basketball", "Basketball", "ph:basketball-fill", List.of("3x3", "5v5")),
			new Sport("volleyball", "Volleyball", "ph:volleyball-fill", List.of("6v6", "Beach 2v2")),
			new Sport("tennis", "Tennis", "ph:tennis-ball-fill", List.of("Singles", "Doubles")));

	private SportCatalog() {
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
