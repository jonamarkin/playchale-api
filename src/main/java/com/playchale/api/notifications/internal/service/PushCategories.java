package com.playchale.api.notifications.internal.service;

import java.util.List;
import java.util.Map;

/**
 * The kinds of notification someone can keep off their phone, each a group of notification kinds.
 * The web app shows the same groups.
 */
final class PushCategories {

	static final List<String> ALL = List.of("games", "payments", "results", "teams", "bookings");

	private static final Map<String, String> BY_KIND = Map.ofEntries(Map.entry("game-invite", "games"), Map.entry("player-joined", "games"),
			Map.entry("game-full", "games"), Map.entry("removed-from-game", "games"), Map.entry("game-cancelled", "games"),
			Map.entry("game-moved", "games"), Map.entry("series", "games"), Map.entry("invite-declined", "games"), Map.entry("game-message", "games"), Map.entry("payment-reminder", "payments"),
			Map.entry("payment-received", "payments"), Map.entry("result-added", "results"), Map.entry("result-disputed", "results"),
			Map.entry("squad-request", "teams"), Map.entry("squad-reply", "teams"), Map.entry("booking", "bookings"));

	private PushCategories() {
	}

	/** The group a notification kind is in, or null for a kind that's never kept off. */
	static String of(String kind) {
		return BY_KIND.get(kind);
	}

}
