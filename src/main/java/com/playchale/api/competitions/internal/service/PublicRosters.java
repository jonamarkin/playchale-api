package com.playchale.api.competitions.internal.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who a company has playing, as everyone else may see it: the names of the players its manager put
 * forward and the organiser approved.
 *
 * <p>Only those three things leave here — the name, whether they're on PlayChale, and which side
 * they play for. The employee reference, the attestation, who reviewed it and why anyone was turned
 * down stay inside the operations screens, because a public page is no place to say that a named
 * person was refused.
 */
@Component
class PublicRosters {

	private final JdbcClient jdbc;

	PublicRosters(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** An approved player on a company's roster. {@code userId} is set once they've claimed the place. */
	record PublicPlayer(String displayName, UUID userId) {
	}

	/** Approved players by team, for one competition. Empty for a competition with no rosters. */
	@Transactional(readOnly = true)
	Map<UUID, List<PublicPlayer>> of(UUID competitionId) {
		record Row(UUID teamId, String displayName, UUID userId) {
		}
		return jdbc.sql("""
				SELECT team_id, display_name, user_id FROM roster_members
				WHERE competition_id = :competition AND eligibility_state = 'approved'
				ORDER BY display_name
				""")
			.param("competition", competitionId)
			.query((rs, n) -> new Row((UUID) rs.getObject("team_id"), rs.getString("display_name"), (UUID) rs.getObject("user_id")))
			.list()
			.stream()
			.collect(Collectors.groupingBy(Row::teamId, Collectors.mapping(r -> new PublicPlayer(r.displayName(), r.userId()), Collectors.toList())));
	}

}
