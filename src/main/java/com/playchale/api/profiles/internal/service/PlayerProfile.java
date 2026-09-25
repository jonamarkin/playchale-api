package com.playchale.api.profiles.internal.service;

import java.util.List;

import com.playchale.api.users.api.UserSummary;

/**
 * A player's public profile, as the web app's PlayerProfile type.
 *
 * @param stats   across every sport: only games, wins and win rate mean the same everywhere
 * @param form    latest results across all sports, oldest first, at most five ("W", "D", "L")
 * @param bySport every sport they list or have results in, most played first
 * @param teams   teams they play for in competitions
 */
public record PlayerProfile(UserSummary user, PlayerStats stats, List<String> form, List<SportRecord> bySport,
		List<String> teams) {
}
