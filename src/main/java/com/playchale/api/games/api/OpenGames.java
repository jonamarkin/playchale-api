package com.playchale.api.games.api;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Games anyone could still join, for telling people about the ones near them (Thursday's and
 * Friday's "this weekend near you"), and who's hosting, for asking a host with nothing on to put one up.
 */
public interface OpenGames {

	/**
	 * A public game still taking players.
	 *
	 * @param spotsLeft null for a game with no limit
	 * @param playerIds everyone in it with an account, the host too
	 */
	record OpenGame(UUID id, String title, String sport, String format, String venueName, String venueArea, String country, Instant startsAt,
			String timezone, Integer spotsLeft, UUID hostId, Set<UUID> playerIds) {
	}

	/** Public games, not a competition's, with room, starting from {@code from} until {@code to}, soonest first. */
	List<OpenGame> between(Instant from, Instant to);

	/** Who hosted a game (not called off) that started from {@code since} until now: the hosts worth asking. */
	Set<UUID> hostedSince(Instant since, Instant now);

	/** Who has something on from {@code from} until {@code to}: a game they host there, or a game that repeats. */
	Set<UUID> hosting(Instant from, Instant to);

	/** Who's in a game (not called off) starting from {@code from} until {@code to}: already playing then. */
	Set<UUID> playing(Instant from, Instant to);

}
