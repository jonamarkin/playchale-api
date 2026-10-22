package com.playchale.api.games.internal.domain;

import java.time.Instant;
import java.util.List;

/**
 * Where the player of the match vote stands, as one viewer may see it.
 *
 * @param cast     picks that count so far
 * @param eligible players who can pick: everyone on a side with an account
 * @param myPick   the viewer's own pick, or null
 * @param winners  once voting has closed, whoever has the most picks (several when level); empty
 *                 before then, or with no picks
 * @param votes    the winners' picks each
 */
public record VoteTally(Instant closesAt, boolean open, int cast, int eligible, String myPick, List<String> winners, int votes) {
}
