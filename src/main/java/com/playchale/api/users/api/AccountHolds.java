package com.playchale.api.users.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Something that stops a player deleting their account yet, because other people depend on it (a
 * game they're hosting, a venue they run). Declared here and implemented by the module that knows,
 * so the users module never has to depend on them.
 */
public interface AccountHolds {

	/** Why this player can't delete their account yet, in words for them; empty if nothing here stops them. */
	Optional<String> reasonToWait(UUID userId);

}
