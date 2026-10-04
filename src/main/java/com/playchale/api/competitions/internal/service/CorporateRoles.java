package com.playchale.api.competitions.internal.service;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The jobs people hold in a company league besides organising it or playing in it: running one
 * company's entry, refereeing its fixtures, or holding a seat in the workspace.
 *
 * <p>These live in tables with no entity of their own, so they are read here rather than bent into
 * the competition's mapping. Without this a company's manager and a match official could not find
 * the competition at all — their part in it was invisible to every list in the app.
 */
@Component
class CorporateRoles {

	private final JdbcClient jdbc;

	CorporateRoles(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** One company a person enters and keeps the roster for. */
	record ManagedTeam(UUID teamId, String teamName) {
	}

	/** Competitions this person has a part in beyond organising or playing. */
	@Transactional(readOnly = true)
	List<UUID> competitionsFor(UUID userId) {
		return jdbc.sql("""
				SELECT DISTINCT c.id
				FROM competitions c
				WHERE EXISTS (SELECT 1 FROM competition_entry_managers m WHERE m.competition_id = c.id AND m.user_id = :user)
				   OR EXISTS (SELECT 1 FROM competition_staff s WHERE s.competition_id = c.id AND s.user_id = :user)
				   OR EXISTS (SELECT 1 FROM organisation_memberships o
				              WHERE o.organisation_id = c.organisation_id AND o.user_id = :user
				                AND o.role IN ('owner', 'admin'))
				   OR EXISTS (SELECT 1 FROM fixture_officials f JOIN games g ON g.id = f.game_id
				              WHERE g.competition_id = c.id AND f.user_id = :user)
				""").param("user", userId).query(UUID.class).list();
	}

	/** Whether they referee any of this competition's fixtures. */
	@Transactional(readOnly = true)
	boolean officiates(UUID competitionId, UUID userId) {
		if (userId == null) {
			return false;
		}
		return jdbc.sql("""
				SELECT count(*) FROM fixture_officials f JOIN games g ON g.id = f.game_id
				WHERE g.competition_id = :competition AND f.user_id = :user
				""").param("competition", competitionId).param("user", userId).query(Integer.class).single() > 0;
	}

	/** Whether they keep any company's roster here. */
	@Transactional(readOnly = true)
	boolean managesAnEntry(UUID competitionId, UUID userId) {
		if (userId == null) {
			return false;
		}
		return jdbc.sql("SELECT count(*) FROM competition_entry_managers WHERE competition_id = :competition AND user_id = :user")
			.param("competition", competitionId).param("user", userId).query(Integer.class).single() > 0;
	}

	/** The companies this person enters in one competition, in name order. Empty if they run none. */
	@Transactional(readOnly = true)
	List<ManagedTeam> teamsFor(UUID competitionId, UUID userId) {
		return jdbc.sql("""
				SELECT t.id AS team_id, t.name AS team_name
				FROM competition_entry_managers m
				JOIN teams t ON t.id = m.team_id
				WHERE m.competition_id = :competition AND m.user_id = :user
				ORDER BY t.name
				""").param("competition", competitionId).param("user", userId).query(ManagedTeam.class).list();
	}

}
