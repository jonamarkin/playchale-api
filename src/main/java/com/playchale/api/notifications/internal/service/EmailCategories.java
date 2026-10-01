package com.playchale.api.notifications.internal.service;

import java.util.List;
import java.util.Map;

/**
 * The kinds of email someone can keep out of their inbox, each a group of notification kinds. The
 * same groups as {@link PushCategories} so the web app can show one set of switches per channel and
 * people recognise them, plus {@code competitions}: a league announcement is worth an email in a way
 * that "someone joined your game" is not, and it is the one kind people ask to be able to stop.
 */
final class EmailCategories {

	static final List<String> ALL = List.of("games", "payments", "results", "teams", "bookings", "competitions");

	private static final Map<String, String> BY_KIND = Map.ofEntries(Map.entry("game-invite", "games"), Map.entry("player-joined", "games"),
			Map.entry("game-full", "games"), Map.entry("removed-from-game", "games"), Map.entry("game-cancelled", "games"),
			Map.entry("game-moved", "games"), Map.entry("invite-declined", "games"), Map.entry("game-reminder", "games"),
			Map.entry("payment-reminder", "payments"), Map.entry("payment-received", "payments"), Map.entry("result-added", "results"),
			Map.entry("result-disputed", "results"), Map.entry("squad-request", "teams"), Map.entry("squad-reply", "teams"),
			Map.entry("booking", "bookings"), Map.entry("competition-announcement", "competitions"));

	private EmailCategories() {
	}

	/** The group a notification kind is in, or null for a kind that's never kept out. */
	static String of(String kind) {
		return BY_KIND.get(kind);
	}

	static boolean known(String category) {
		return ALL.contains(category);
	}

}
