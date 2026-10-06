package com.playchale.api.profiles.internal.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.games.internal.domain.GameDetails;
import com.playchale.api.games.internal.domain.ResultInput;
import com.playchale.api.games.internal.domain.ResultInput.Scorer;
import com.playchale.api.games.internal.domain.SetScore;
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

/** Who is top, and what the filters do to the answer. */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, LeaderboardTest.Clocks.class })
class LeaderboardTest {

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
	Leaderboard leaderboard;

	@Autowired
	GameService games;

	@Autowired
	ResultService results;

	@Autowired
	UserDirectory users;

	@Autowired
	TestClock clock;

	@Autowired
	JdbcClient jdbc;

	UUID kwame;

	UUID kojo;

	UUID ama;

	@BeforeEach
	void setUp() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, games, game_participants, notifications, game_results CASCADE").update();
		clock.set(NOW);
		kwame = user("+233244555123", "Kwame Mensah");
		kojo = user("+233244555124", "Kojo Owusu");
		ama = user("+233244555125", "Ama Serwaa");
	}

	private UUID user(String phone, String name) {
		var id = users.registerOrFind(phone, "GH").id();
		jdbc.sql("UPDATE users SET name = :name WHERE id = :id").param("name", name).param("id", id).update();
		return id;
	}

	/** A played game with a result: Kwame and Kojo beat Ama, with the given scorers. */
	private void played(String sport, String area, int hoursAgo, List<Scorer> scorers) {
		clock.set(NOW.minus(Duration.ofHours(hoursAgo + 2L)));
		var game = games.create(new GameDetails(sport, sport.equals("football") ? "5-a-side" : "3x3", null,
				clock.instant().plus(Duration.ofHours(1)), 60, "unlisted", null, null, "A pitch", area, null, 10, 0, null, "public", null),
				kwame);
		games.join(game.id(), kojo);
		games.join(game.id(), ama);
		clock.set(NOW.minus(Duration.ofHours(hoursAgo)));
		results.record(game.id(), new ResultInput(3, 1, List.of(kwame.toString(), kojo.toString()), List.of(ama.toString()), scorers, null,
				null), kwame);
		clock.set(NOW);
	}

	private List<String> namesOf(List<Leaderboard.Standing> table) {
		return table.stream().map(s -> s.player().name()).toList();
	}

	@Test
	void theTableRanksByWhatPlayersActuallyDid() {
		played("football", "Osu, Accra", 24, List.of(new Scorer(kojo.toString(), 3, 0, null)));
		played("football", "Osu, Accra", 48, List.of(new Scorer(kwame.toString(), 1, 2, null)));

		var goals = leaderboard.top(null, null, null, Leaderboard.Period.ALL, "goals", 10);
		assertThat(namesOf(goals)).containsExactly("Kojo Owusu", "Kwame Mensah");
		assertThat(goals.get(0).goals()).isEqualTo(3);
		assertThat(goals.get(1).assists()).as("assists travel with the player, whatever the table is sorted by").isEqualTo(2);

		// Ama lost both and never scored, so she is on no goals table — and on the games one.
		assertThat(namesOf(leaderboard.top(null, null, null, Leaderboard.Period.ALL, "games", 10))).contains("Ama Serwaa");
	}

	@Test
	void playersLevelOnTheMetricShareAPlace() {
		played("football", "Osu, Accra", 24, List.of(new Scorer(kojo.toString(), 2, 0, null)));
		played("football", "Osu, Accra", 48, List.of(new Scorer(kwame.toString(), 2, 0, null)));

		var table = leaderboard.top(null, null, null, Leaderboard.Period.ALL, "goals", 10);
		assertThat(table).extracting(Leaderboard.Standing::place).as("two on two goals are both first").containsExactly(1, 1);
	}

	@Test
	void aSportOnlyCountsItsOwnGames() {
		played("football", "Osu, Accra", 24, List.of(new Scorer(kojo.toString(), 3, 0, null)));
		played("basketball", "Osu, Accra", 24, List.of());

		assertThat(namesOf(leaderboard.top("football", null, null, Leaderboard.Period.ALL, "goals", 10))).containsExactly("Kojo Owusu");
		assertThat(leaderboard.top("basketball", null, null, Leaderboard.Period.ALL, "goals", 10)).isEmpty();
		assertThat(leaderboard.top("quidditch", null, null, Leaderboard.Period.ALL, "goals", 10)).as("an unknown sport is empty, not an error")
			.isEmpty();
	}

	@Test
	void aPlaceOnlyCountsGamesPlayedThere() {
		played("football", "Osu, Accra", 24, List.of(new Scorer(kojo.toString(), 3, 0, null)));
		played("football", "Labone, Accra", 24, List.of(new Scorer(kwame.toString(), 2, 0, null)));

		assertThat(namesOf(leaderboard.top(null, null, "Osu", Leaderboard.Period.ALL, "goals", 10))).containsExactly("Kojo Owusu");
		assertThat(namesOf(leaderboard.top(null, null, "Labone", Leaderboard.Period.ALL, "goals", 10))).containsExactly("Kwame Mensah");
		assertThat(leaderboard.areas(null)).contains("Osu, Accra", "Labone, Accra");
	}

	@Test
	void aPeriodRunsFromTheStartOfItNotAWindowBackwards() {
		// NOW is the 25th of September, so "this month" starts on the 1st: a game on the 24th is in,
		// one in August is not, and both are inside the year.
		played("football", "Osu, Accra", 24, List.of(new Scorer(kojo.toString(), 1, 0, null)));
		played("football", "Osu, Accra", 24 * 40, List.of(new Scorer(kwame.toString(), 3, 0, null)));

		assertThat(namesOf(leaderboard.top(null, null, null, Leaderboard.Period.MONTH, "goals", 10))).containsExactly("Kojo Owusu");
		assertThat(namesOf(leaderboard.top(null, null, null, Leaderboard.Period.YEAR, "goals", 10)))
			.containsExactly("Kwame Mensah", "Kojo Owusu");
	}

	@Test
	void setBasedSportsCountSetsAndStillCountGamesOnce() {
		// Volleyball keeps no player stats at all: sets are the only thing of its own it has.
		clock.set(NOW.minus(Duration.ofHours(26)));
		var game = games.create(new GameDetails("volleyball", "6v6", null, clock.instant().plus(Duration.ofHours(1)), 60, "unlisted", null,
				null, "A court", "Osu, Accra", null, 10, 0, null, "public", null), kwame);
		games.join(game.id(), kojo);
		games.join(game.id(), ama);
		clock.set(NOW.minus(Duration.ofHours(24)));
		results.record(game.id(), new ResultInput(0, 0, List.of(kwame.toString(), kojo.toString()), List.of(ama.toString()), null,
				List.of(new SetScore(25, 20), new SetScore(23, 25), new SetScore(25, 18)), null), kwame);
		clock.set(NOW);

		var table = leaderboard.top("volleyball", null, null, Leaderboard.Period.ALL, "sets", 10);
		assertThat(table).isNotEmpty();
		var winner = table.get(0);
		assertThat(winner.sets()).as("two sets of three").isEqualTo(2);
		// The join that finds sets must not multiply the player's row into three games.
		assertThat(winner.games()).as("one game, however many sets it ran to").isEqualTo(1);

		var loser = table.stream().filter(r -> r.player().name().equals("Ama Serwaa")).findFirst();
		assertThat(loser).hasValueSatisfying(r -> assertThat(r.sets()).isEqualTo(1));
	}

	@Test
	void theTableNeverHandsOutPhoneNumbers() {
		played("football", "Osu, Accra", 24, List.of(new Scorer(kojo.toString(), 3, 0, null)));
		var top = leaderboard.top(null, null, null, Leaderboard.Period.ALL, "goals", 10).get(0).player();
		assertThat(top.name()).isEqualTo("Kojo Owusu");
		assertThat(top.phone()).as("a public table is the last place for someone's number").isEmpty();
		assertThat(top.email()).isNull();
	}

}
