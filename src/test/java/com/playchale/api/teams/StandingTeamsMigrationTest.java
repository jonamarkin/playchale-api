package com.playchale.api.teams;

import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V17 turns league teams into standing teams. A league set up before it keeps its teams (same ids,
 * so fixtures stay linked), squads, and requests.
 */
class StandingTeamsMigrationTest {

	private static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17"));

	@BeforeAll
	static void start() {
		postgres.start();
	}

	@AfterAll
	static void stop() {
		postgres.stop();
	}

	@Test
	void leagueTeamsBecomeStandingTeamsWithTheirSquads() {
		var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
		Flyway.configure().dataSource(source).target("16").load().migrate();
		var jdbc = JdbcClient.create(source);
		jdbc.sql("""
				INSERT INTO users (id, phone, country, name, tint) VALUES
				  ('00000000-0000-0000-0000-000000000001', '+233244000001', 'GH', 'Sam', '#7cf0c8'),
				  ('00000000-0000-0000-0000-000000000002', '+233244000002', 'GH', 'Kojo', '#7cf0c8'),
				  ('00000000-0000-0000-0000-000000000003', '+233244000003', 'GH', 'Ama', '#7cf0c8'),
				  ('00000000-0000-0000-0000-000000000004', '+233244000004', 'GH', 'Esi', '#7cf0c8');
				INSERT INTO competitions (id, name, sport, format, organiser_id, venue_kind, venue_name, starts_at, duration_minutes,
				                          status, created_at, updated_at)
				VALUES ('00000000-0000-0000-0000-00000000000c', 'Office League', 'football', '5-a-side',
				        '00000000-0000-0000-0000-000000000001', 'unlisted', 'Legon Park', now(), 60, 'running', now(), now());
				INSERT INTO teams (id, competition_id, name, captain_id, tint, join_token, created_at) VALUES
				  ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-00000000000c', 'Reds',
				   '00000000-0000-0000-0000-000000000002', '#7cf0c8', 'reds-token', now()),
				  ('00000000-0000-0000-0000-0000000000a2', '00000000-0000-0000-0000-00000000000c', 'Blues',
				   '00000000-0000-0000-0000-000000000001', '#a9c4f2', 'blues-token', now());
				INSERT INTO team_players (team_id, competition_id, user_id, added_at) VALUES
				  ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-00000000000c', '00000000-0000-0000-0000-000000000002', now()),
				  ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-00000000000c', '00000000-0000-0000-0000-000000000003', now());
				INSERT INTO join_requests (id, competition_id, team_id, user_id, status, created_at) VALUES
				  ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-00000000000c', '00000000-0000-0000-0000-0000000000a1',
				   '00000000-0000-0000-0000-000000000004', 'pending', now());
				""").update();

		Flyway.configure().dataSource(source).load().migrate();

		assertThat(jdbc.sql("SELECT name FROM teams ORDER BY name").query(String.class).list()).containsExactly("Blues", "Reds");
		assertThat(jdbc.sql("SELECT team_id::text, status FROM competition_entries ORDER BY team_id").query().listOfRows())
			.as("both teams are still in the league, with their ids")
			.containsExactly(Map.of("team_id", "00000000-0000-0000-0000-0000000000a1", "status", "entered"),
					Map.of("team_id", "00000000-0000-0000-0000-0000000000a2", "status", "entered"));
		assertThat(jdbc.sql("SELECT user_id::text FROM team_members WHERE team_id = '00000000-0000-0000-0000-0000000000a1' ORDER BY user_id")
			.query(String.class).list())
			.containsExactly("00000000-0000-0000-0000-000000000002", "00000000-0000-0000-0000-000000000003");
		assertThat(jdbc.sql("SELECT count(*) FROM team_members WHERE team_id = '00000000-0000-0000-0000-0000000000a2'").query(Long.class).single())
			.as("an organiser's team with no players listed stays empty").isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM entry_players WHERE competition_id = '00000000-0000-0000-0000-00000000000c'")
			.query(Long.class).single()).as("the league squad").isEqualTo(2);
		assertThat(jdbc.sql("SELECT team_id::text FROM team_join_requests WHERE status = 'pending'").query(String.class).list())
			.isEqualTo(List.of("00000000-0000-0000-0000-0000000000a1"));
	}

}
