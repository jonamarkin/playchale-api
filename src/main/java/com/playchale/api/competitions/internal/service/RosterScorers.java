package com.playchale.api.competitions.internal.service;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who has scored in a company league, from the official match sheets.
 *
 * <p>The ordinary scorers chart is built from the result tables, which only know people with a
 * PlayChale account. A company enters a staff list, so most of its players have none — which left
 * the chart permanently empty on exactly the competition it matters most to, saying "it fills in as
 * hosts record who scored" when it never could. Here the roster member is the player, named as the
 * company named them, whether or not they have ever opened the app.
 */
@Component
class RosterScorers {

	private final JdbcClient jdbc;

	RosterScorers(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * @param userId set once the player has claimed their place, so the row can be theirs
	 * @param games  match sheets they were checked in on
	 */
	record RosterScorer(UUID rosterMemberId, String displayName, UUID userId, UUID teamId, int goals, int assists, int games) {
	}

	/** Everyone who scored or assisted, best first. Only submitted sheets count. */
	@Transactional(readOnly = true)
	List<RosterScorer> of(UUID competitionId) {
		return jdbc.sql("""
				SELECT r.id, r.display_name, r.user_id, r.team_id,
				       coalesce(sum(p.goals), 0) AS goals,
				       coalesce(sum(p.assists), 0) AS assists,
				       count(*) FILTER (WHERE p.checked_in) AS games
				FROM match_sheet_players p
				JOIN roster_members r ON r.id = p.roster_member_id
				JOIN games g ON g.id = p.game_id
				JOIN fixture_match_sheets s ON s.game_id = g.id
				WHERE g.competition_id = :competition AND s.status = 'submitted'
				GROUP BY r.id, r.display_name, r.user_id, r.team_id
				HAVING coalesce(sum(p.goals), 0) > 0 OR coalesce(sum(p.assists), 0) > 0
				ORDER BY goals DESC, assists DESC, games, r.display_name
				""")
			.param("competition", competitionId)
			.query((rs, n) -> new RosterScorer((UUID) rs.getObject("id"), rs.getString("display_name"), (UUID) rs.getObject("user_id"),
					(UUID) rs.getObject("team_id"), rs.getInt("goals"), rs.getInt("assists"), rs.getInt("games")))
			.list();
	}

}
