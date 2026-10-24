package com.playchale.api.auth.api;

import java.time.Instant;
import java.util.Set;

/** Who has used PlayChale lately, from their sessions. For noticing who hasn't. */
public interface Presence {

	/** Everyone with a session used from {@code since} on, signed in on any device. */
	Set<java.util.UUID> seenSince(Instant since);

}
