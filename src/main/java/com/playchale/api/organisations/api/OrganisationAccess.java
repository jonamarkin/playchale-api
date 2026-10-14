package com.playchale.api.organisations.api;

import java.util.Optional;
import java.util.UUID;

/** The small authorization surface other modules use; organisation internals remain private. */
public interface OrganisationAccess {

	void requireAdmin(UUID organisationId, UUID userId);

	boolean corporateEnabled(UUID organisationId);

	record Brand(UUID id, String name, String primaryColour, String logoUrl) { }

	Brand brand(UUID organisationId);

	boolean isAdmin(UUID organisationId, UUID userId);

	/** Their seat in the workspace (owner, admin or official), or empty if they have none. */
	Optional<String> roleOf(UUID organisationId, UUID userId);

	/**
	 * A new workspace that {@code userId} owns, its address made from the name (and numbered when
	 * taken): for someone setting up their first event, who never needs to see the address. The
	 * caller's transaction holds it, so an event that then fails leaves no empty workspace behind.
	 */
	UUID createFor(UUID userId, String name, String country, String colour);
}

