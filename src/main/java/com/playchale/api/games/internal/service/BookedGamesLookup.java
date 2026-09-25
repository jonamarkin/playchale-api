package com.playchale.api.games.internal.service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.playchale.api.games.internal.repository.GameRepository;
import com.playchale.api.venues.api.BookedGames;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Tells the venues module what the games booked on its pitches are, for owners' schedules. */
@Component
class BookedGamesLookup implements BookedGames {

	private final GameRepository games;

	BookedGamesLookup(GameRepository games) {
		this.games = games;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, BookedGame> describe(Collection<UUID> gameIds) {
		return games.findAllById(gameIds).stream()
			.collect(Collectors.toMap(g -> g.getId(), g -> new BookedGame(g.getTitle(), g.getParticipants().size())));
	}

}
