package com.playchale.api.catalog.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One position (or, for athletics, one event) a player can pick for a sport.
 *
 * @param id        stable key stored with the player, e.g. "goalkeeper". Never renamed.
 * @param label     what the app shows, e.g. "Goalkeeper". Free to change.
 * @param group     heading it's listed under when a sport has a long list (athletics: "Sprints"), or null
 * @param exclusive true for "Anywhere": it can't be picked together with anything else
 */
public record RoleOption(String id, String label, @JsonInclude(JsonInclude.Include.NON_NULL) String group,
		@JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean exclusive) {

	public static RoleOption of(String id, String label) {
		return new RoleOption(id, label, null, false);
	}

	/** "Anywhere": for players without a fixed spot. */
	public static RoleOption anywhere() {
		return new RoleOption("anywhere", "Anywhere", null, true);
	}

}
