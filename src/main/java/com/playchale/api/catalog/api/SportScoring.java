package com.playchale.api.catalog.api;

import java.util.List;
import java.util.Map;

/**
 * How a sport's result is recorded. Mirrors {@code sportScoring} in webapp/app/data/sports.ts.
 *
 * @param sets          true when it's scored set by set (volleyball, tennis): the match score is then
 *                      sets won
 * @param max           highest score one side can have: the whole game, or one set
 * @param maxSets       most sets in a match (set-based sports)
 * @param playerStats   the per-player numbers a host can record, e.g. goals and assists
 * @param countsToScore the player stat that makes up the score, so a side's total can't exceed it
 */
public record SportScoring(boolean sets, int max, int maxSets, List<String> playerStats, String countsToScore) {

	/** Highest number one player can have in one game, per stat. */
	public static final Map<String, Integer> STAT_MAX = Map.of("goals", 20, "assists", 20, "points", 99);

}
