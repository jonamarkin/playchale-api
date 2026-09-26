package com.playchale.api.teams.api;

import java.util.List;
import java.util.UUID;

/**
 * The leagues a team is in, from the competitions module. Declared here and implemented there, so
 * teams doesn't depend on competitions (which depends on teams).
 */
public interface TeamLeagues {

	/**
	 * @param status league status: draft, running or finished
	 * @param entry  the team's place in it: invited (waiting for the captain) or entered
	 */
	record TeamLeague(UUID competitionId, String name, String status, String entry) {

		/**
		 * Fixtures drawn (running or finished): the league's table and games need the team, so it
		 * can't be deleted. Leaving a league still being set up just takes it out.
		 */
		public boolean started() {
			return !"draft".equals(status);
		}

	}

	List<TeamLeague> leaguesOf(UUID teamId);

}
