package com.playchale.api.competitions.api;

import java.util.List;
import java.util.UUID;

/** The teams a player plays for, for their profile. */
public interface TeamMemberships {

	List<String> teamNames(UUID userId);

}
