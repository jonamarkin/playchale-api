package com.playchale.api.catalog.api;

import java.util.List;

/**
 * A sport, as the web app's Sport type expects it (webapp/app/types/domain.ts).
 *
 * @param id      stable key stored with profiles and games, e.g. "football"
 * @param icon    icon name the web app draws
 * @param formats formats offered when creating a game, e.g. "5-a-side"
 */
public record Sport(String id, String label, String icon, List<String> formats) {

	public Sport {
		formats = List.copyOf(formats);
	}

}
