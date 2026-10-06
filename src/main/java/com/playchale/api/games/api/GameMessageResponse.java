package com.playchale.api.games.api;

import java.time.Instant;
import java.util.UUID;

import com.playchale.api.users.api.UserSummary;

/**
 * Something said about a game, as the people in that game see it. Only ever sent to them.
 *
 * @param said who said it, as the web app's User type
 */
public record GameMessageResponse(UUID id, UserSummary said, String body, Instant at) {
}
