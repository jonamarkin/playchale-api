package com.playchale.api.profiles.internal.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.games.api.PlayedGames;
import com.playchale.api.games.api.PlayedGames.PlayedGame;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.users.api.UserSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Player profiles: who they are, plus their record, worked out from verified results. A game counts
 * for a player when they were on a side; not turning up doesn't count against them, or for them.
 */
@Service
public class PlayerProfileService {

	/** How many recent results "form" shows. */
	private static final int FORM = 5;

	private final UserDirectory users;

	private final PlayedGames played;

	PlayerProfileService(UserDirectory users, PlayedGames played) {
		this.users = users;
		this.played = played;
	}

	/** profiles.get. Phone numbers are only included when the viewer is the player. */
	@Transactional(readOnly = true)
	public PlayerProfile get(UUID userId, Optional<UUID> viewer) {
		return users.find(userId)
			.map(user -> profileOf(user, viewer))
			.orElseThrow(() -> BusinessException.notFound("That player could not be found."));
	}

	/** profiles.getByHandle */
	@Transactional(readOnly = true)
	public Optional<PlayerProfile> getByHandle(String handle, Optional<UUID> viewer) {
		return users.findByHandle(handle).map(user -> profileOf(user, viewer));
	}

	/** profiles.history: verified results they played in, newest first. */
	@Transactional(readOnly = true)
	public List<MatchRecord> history(UUID userId) {
		return played.by(userId).stream()
			.map(g -> new MatchRecord(g.gameId(), g.title(), g.sport(), g.format(), g.startsAt(), g.venueName(), g.outcome(), g.scoreFor(),
					g.scoreAgainst(), g.goals(), g.assists(), g.points(),
					g.sets().isEmpty() ? null : g.sets().stream().map(s -> new MatchRecord.SetScore(s.home(), s.away())).toList()))
			.toList();
	}

	private PlayerProfile profileOf(UserSummary user, Optional<UUID> viewer) {
		var shown = viewer.map(user::as).orElseGet(user::toPublic);
		var games = new ArrayList<>(played.by(user.id()));
		games.sort(Comparator.comparing(PlayedGame::startsAt));

		// Every sport they list, then any others they have results in; most played first.
		var bySport = new LinkedHashMap<String, List<PlayedGame>>();
		user.sports().forEach(sport -> bySport.put(sport, new ArrayList<>()));
		games.forEach(g -> bySport.computeIfAbsent(g.sport(), s -> new ArrayList<>()).add(g));
		var records = bySport.entrySet().stream()
			.map(e -> new SportRecord(e.getKey(), stats(e.getValue()), form(e.getValue())))
			.sorted(Comparator.comparingInt((SportRecord r) -> r.stats().games()).reversed())
			.toList();

		return new PlayerProfile(shown, stats(games), form(games), records, List.of());
	}

	private static PlayerStats stats(List<PlayedGame> games) {
		return new PlayerStats(games.size(), (int) games.stream().filter(g -> "W".equals(g.outcome())).count(),
				games.stream().mapToInt(PlayedGame::goals).sum(), games.stream().mapToInt(PlayedGame::assists).sum(),
				games.stream().mapToInt(PlayedGame::points).sum(), games.stream().mapToInt(PlayedGame::setsWon).sum());
	}

	/** The latest results, oldest first. {@code games} is in date order. */
	private static List<String> form(List<PlayedGame> games) {
		return games.subList(Math.max(0, games.size() - FORM), games.size()).stream().map(PlayedGame::outcome).toList();
	}

}
