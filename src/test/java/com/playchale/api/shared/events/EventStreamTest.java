package com.playchale.api.shared.events;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.games.internal.domain.GameDetails;
import com.playchale.api.games.internal.domain.ResultInput;
import com.playchale.api.games.internal.service.GameService;
import com.playchale.api.games.internal.service.ResultService;
import com.playchale.api.shared.TestClock;
import com.playchale.api.users.api.UserDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The record of what happened, which is the only part of an analytics story that cannot be added
 * later: a row overwritten today is gone whatever is bought next year.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, EventStreamTest.Clocks.class })
class EventStreamTest {

	@TestConfiguration
	static class Clocks {

		@Bean
		@Primary
		TestClock testClock() {
			return new TestClock();
		}

	}

	private static final Instant NOW = Instant.parse("2026-09-25T08:00:00Z");

	@Autowired
	GameService games;

	@Autowired
	ResultService results;

	@Autowired
	Happened happened;

	@Autowired
	UserDirectory users;

	@Autowired
	TestClock clock;

	@Autowired
	JdbcClient jdbc;

	UUID kwame;

	UUID kojo;

	@BeforeEach
	void setUp() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, games, game_participants, notifications, game_results, audit_events CASCADE")
			.update();
		clock.set(NOW);
		kwame = users.registerOrFind("+233244555123", "GH").id();
		kojo = users.registerOrFind("+233244555124", "GH").id();
	}

	private List<String> types() {
		return jdbc.sql("SELECT event_type FROM audit_events ORDER BY occurred_at, event_type").query(String.class).list();
	}

	@Test
	void aGamesLifeIsWrittenDownAsItHappens() {
		var game = games.create(new GameDetails("football", "5-a-side", "Saturday 5s", NOW.plus(Duration.ofHours(4)), 60, "unlisted", null,
				null, "Legon Park", "Legon", null, 2, 0, null, "public", null), kwame);
		games.join(game.id(), kojo);
		clock.set(NOW.plus(Duration.ofHours(6)));
		results.record(game.id(), new ResultInput(3, 1, List.of(kwame.toString()), List.of(kojo.toString()), null, null, null), kwame);

		assertThat(types()).contains("game.joined", "game.result");

		// The join carries what the game's own row will never say again: how full it was at the time.
		var filled = jdbc.sql("SELECT details ->> 'filled' FROM audit_events WHERE event_type = 'game.joined'").query(String.class).single();
		assertThat(filled).as("the second of two spots").isEqualTo("2");
		var full = jdbc.sql("SELECT details ->> 'full' FROM audit_events WHERE event_type = 'game.joined'").query(String.class).single();
		assertThat(full).as("and that it filled the game").isEqualTo("true");
	}

	@Test
	void anEventOutsideAWorkspaceNeedsNoOrganisation() {
		// The table began life pinned to a corporate workspace; most of the app has none.
		happened.record("venue.viewed", "venue", UUID.randomUUID(), kwame, java.util.Map.of("source", "search"));
		assertThat(jdbc.sql("SELECT count(*) FROM audit_events WHERE organisation_id IS NULL").query(Long.class).single()).isOne();
	}

	@Test
	void theAppItselfCanBeTheActor() {
		happened.record("digest.sent", "user", kwame, null, java.util.Map.of("week", 40));
		assertThat(jdbc.sql("SELECT count(*) FROM audit_events WHERE actor_id IS NULL").query(Long.class).single()).isOne();
	}

	@Test
	void failingToWriteItDownNeverFailsWhatHappened() {
		// A subject that breaks the insert must not take the caller down with it.
		happened.record("game.joined", "game", UUID.randomUUID(), UUID.randomUUID(), java.util.Map.of());
		assertThat(types()).as("nothing thrown, nothing recorded").isEmpty();
	}

}
