package com.playchale.api.games.internal.domain;

import java.util.UUID;

/**
 * One player's pick for the player of the match.
 *
 * @param playerKey the roster's key of who they picked: a player's ID, or "guest:<spot>"
 */
public record ResultVote(UUID voterId, String playerKey) {
}
