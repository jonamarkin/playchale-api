package com.playchale.api.games.internal.service;

import java.util.UUID;

import com.playchale.api.games.api.FixtureOrganisers;
import com.playchale.api.games.internal.domain.Game;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Who may run a game. Its host always can. A competition fixture is hosted by whoever made the draw,
 * but a committee shares the work, so any of that competition's organisers can run it too — without
 * this, only one member of a committee could ever record a score.
 *
 * <p>The competitions module answers through {@link FixtureOrganisers}; it's an
 * {@code ObjectProvider} because games must keep working when that module isn't there.
 */
@Component
class FixtureRunners {

	private final ObjectProvider<FixtureOrganisers> organisers;

	FixtureRunners(ObjectProvider<FixtureOrganisers> organisers) {
		this.organisers = organisers;
	}

	boolean runs(Game game, UUID userId) {
		if (game.isHost(userId)) {
			return true;
		}
		if (userId == null || game.getCompetitionId() == null) {
			return false;
		}
		return organisers.stream().findFirst().map(o -> o.organisedBy(game.getCompetitionId(), userId)).orElse(false);
	}

	/**
	 * What to tell someone who may not. An ordinary game is its host's, and says so in whatever words
	 * that operation already used; a fixture belongs to the competition's committee.
	 */
	static String refusal(Game game, String hostOnly, String what) {
		return game.getCompetitionId() == null ? hostOnly : "Only the people running this competition can %s.".formatted(what);
	}

}
