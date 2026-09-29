package com.playchale.api.competitions.internal.service;

import com.playchale.api.games.api.Fixtures;
import com.playchale.api.games.api.GameEvents;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Moves a knockout on when a result goes in. The organiser records who won a tie and the next round
 * draws itself, so a cup runs without anyone having to set up each round by hand.
 */
@Component
class KnockoutAdvance {

	private final CompetitionService competitions;

	private final Fixtures fixtures;

	KnockoutAdvance(CompetitionService competitions, Fixtures fixtures) {
		this.competitions = competitions;
		this.fixtures = fixtures;
	}

	@EventListener
	void onResult(GameEvents.ResultRecorded event) {
		fixtures.competitionOf(event.game().gameId()).ifPresent(competitions::advance);
	}

}
