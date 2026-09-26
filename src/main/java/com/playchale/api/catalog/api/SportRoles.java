package com.playchale.api.catalog.api;

import java.util.List;
import java.util.Optional;

/**
 * What a player can say about how they play a sport: positions for team sports, events for
 * athletics. Sports without any (tennis) have none, and the app doesn't ask.
 *
 * @param label   what the app calls them: "Position", or "Events" for athletics
 * @param max     how many one player may pick; the first they pick is their main one
 * @param options the choices, in the order the app lists them
 */
public record SportRoles(String label, int max, List<RoleOption> options) {

	public SportRoles {
		options = List.copyOf(options);
	}

	public Optional<RoleOption> find(String id) {
		return options.stream().filter(o -> o.id().equals(id)).findFirst();
	}

	/** How the app says it in a message, e.g. "positions" or "events". */
	public String plural() {
		var word = label.toLowerCase();
		return word.endsWith("s") ? word : word + "s";
	}

}
