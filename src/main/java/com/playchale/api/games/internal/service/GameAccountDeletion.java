package com.playchale.api.games.internal.service;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.games.internal.domain.Game;
import com.playchale.api.games.internal.repository.GameRepository;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.users.api.AccountDeleted;
import com.playchale.api.users.api.AccountHolds;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/**
 * Games and a player deleting their account: a host can't leave players without their game, and
 * someone leaving gives up the spots they haven't paid for, so others can have them.
 */
@Component
class GameAccountDeletion implements AccountHolds {

	private final GameRepository games;

	private final Clock clock;

	GameAccountDeletion(GameRepository games, Clock clock) {
		this.games = games;
		this.clock = clock;
	}

	@Override
	public Optional<String> reasonToWait(UUID userId) {
		return games.isHostingUpcoming(userId, clock.instant())
				? Optional.of("You’re hosting a game that hasn’t happened yet. Call it off first, then delete your account.") : Optional.empty();
	}

	/**
	 * Unpaid spots in games still to come are given up. A spot they've paid for stays theirs (shown as
	 * a deleted player): the host has their money, so the spot is still paid for.
	 */
	@EventListener
	void on(AccountDeleted e) {
		var now = clock.instant();
		for (var game : games.involving(e.userId(), Limit.of(1000))) {
			if (!game.hasStarted(now) && !game.isCancelled() && !Game.COMPLETED.equals(game.getStatus())) {
				try {
					game.leave(e.userId());
				}
				catch (BusinessException paidAlready) {
					// Paid: the spot stays.
				}
			}
		}
	}

}
