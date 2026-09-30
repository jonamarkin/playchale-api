package com.playchale.api.games.api;

import java.util.UUID;

/**
 * Who runs a fixture besides the person it's hosted by. A competition's committee shares the work,
 * so any of its organisers can record a result or call a fixture off, not only whoever made the
 * draw. Declared here and implemented by the competitions module, so games can honour that without
 * depending on competitions (which depends on games). An ordinary game has no competition, so
 * nobody but its host runs it.
 */
public interface FixtureOrganisers {

	/** Whether this person runs that competition: the organiser who set it up, or a co-organiser. */
	boolean organisedBy(UUID competitionId, UUID userId);

}
