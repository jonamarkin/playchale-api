package com.playchale.api.teams.internal.service;

import java.util.Optional;
import java.util.UUID;

import com.playchale.api.teams.internal.repository.TeamRepository;
import com.playchale.api.users.api.AccountDeleted;
import com.playchale.api.users.api.AccountHolds;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Teams and a player deleting their account: a captain hands the armband over first (their team
 * mates depend on them), and someone leaving is taken out of every team.
 */
@Component
class TeamAccountDeletion implements AccountHolds {

	private final TeamRepository teams;

	TeamAccountDeletion(TeamRepository teams) {
		this.teams = teams;
	}

	@Override
	public Optional<String> reasonToWait(UUID userId) {
		return teams.findByCaptainId(userId).stream().filter(t -> t.memberIds().stream().anyMatch(m -> !m.equals(userId))).findFirst()
			.map(t -> "You captain %s. Hand the armband to someone in the team first, then delete your account.".formatted(t.getName()));
	}

	@EventListener
	void on(AccountDeleted e) {
		teams.playedForBy(e.userId()).forEach(t -> t.forget(e.userId()));
	}

}
