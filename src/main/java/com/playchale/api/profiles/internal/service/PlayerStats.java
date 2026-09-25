package com.playchale.api.profiles.internal.service;

/**
 * Running totals for a player, as the web app's PlayerStats type. Which ones a screen shows depends
 * on the sport: goals and assists for football, points for basketball, sets for volleyball and tennis.
 */
public record PlayerStats(int games, int wins, int goals, int assists, int points, int setsWon) {

	public static final PlayerStats NONE = new PlayerStats(0, 0, 0, 0, 0, 0);

}
