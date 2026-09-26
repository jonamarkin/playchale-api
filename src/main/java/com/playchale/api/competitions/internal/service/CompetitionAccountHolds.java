package com.playchale.api.competitions.internal.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.competitions.internal.domain.Competition;
import com.playchale.api.competitions.internal.repository.CompetitionRepository;
import com.playchale.api.users.api.AccountHolds;
import org.springframework.stereotype.Component;

/** An organiser's account can't go while their league is still being set up or played. */
@Component
class CompetitionAccountHolds implements AccountHolds {

	private final CompetitionRepository competitions;

	CompetitionAccountHolds(CompetitionRepository competitions) {
		this.competitions = competitions;
	}

	@Override
	public Optional<String> reasonToWait(UUID userId) {
		return competitions.existsByOrganiserIdAndStatusIn(userId, List.of(Competition.DRAFT, Competition.RUNNING))
				? Optional.of("You organise a league that’s still going. Get in touch and we’ll sort it out with you before your account goes.")
				: Optional.empty();
	}

}
