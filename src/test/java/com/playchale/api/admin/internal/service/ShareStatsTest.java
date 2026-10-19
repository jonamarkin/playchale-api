package com.playchale.api.admin.internal.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.games.internal.domain.GameDetails;
import com.playchale.api.games.internal.domain.ResultInput;
import com.playchale.api.games.internal.domain.ResultInput.Scorer;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the desk's share images are drawn from: real numbers, and people by their first name only,
 * since the images go out as promotion. Staff only, like everything on the desk.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, ShareStatsTest.Clocks.class })
class ShareStatsTest {

	@TestConfiguration
	static class Clocks {

		@Bean
		@Primary
		TestClock testClock() {
			return new TestClock();
		}

	}

	/** Sunday evening in Accra. */
	private static final Instant NOW = Instant.parse("2026-10-18T19:00:00Z");

	@Autowired
	ShareStats share;

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

	UUID owner;

	UUID kwame;

	UUID ama;

	@BeforeEach
	void setUp() {
		jdbc.sql("TRUNCATE users, sessions, games, game_participants, notifications, game_results, platform_staff, rate_limits CASCADE").update();
		clock.set(NOW);
		owner = user("+233244555701", "Esi Owner");
		kwame = user("+233244555702", "Kwame Mensah");
		ama = user("+233244555703", "Ama Serwaa");
		jdbc.sql("INSERT INTO platform_staff (user_id, role) VALUES (:id, 'owner')").param("id", owner).update();
	}

	private UUID user(String phone, String name) {
		var id = users.registerOrFind(phone, "GH").id();
		jdbc.sql("UPDATE users SET name = :name, onboarded = true WHERE id = :id").param("name", name).param("id", id).update();
		return id;
	}

	/** Kwame hosts at Legon Park; Ama and a guest join; it ends 2–2. */
	private UUID drawYesterday() {
		clock.set(NOW.minus(Duration.ofHours(27)));
		var game = games.create(new GameDetails("football", "5-a-side", "Saturday 5s", clock.instant().plus(Duration.ofHours(1)), 60, "unlisted",
				null, null, "Legon Park", "Legon", null, 10, 0, null, "public", null), kwame).id();
		games.join(game, ama);
		var guest = games.addGuest(game, "Yaw Boateng", null, kwame).spot();
		clock.set(NOW.minus(Duration.ofHours(24)));
		results.record(game, new ResultInput(2, 2, List.of(kwame.toString(), "guest:" + guest), List.of(ama.toString()),
				List.of(new Scorer(kwame.toString(), 2, 0, null), new Scorer(ama.toString(), 2, 0, null)), null, null), kwame);
		clock.set(NOW);
		return game;
	}

	@Test
	void aMatchResultHasItsSidesAndFirstNamesOnly() {
		var game = drawYesterday();
		var result = share.result(owner, game);
		assertThat(result.title()).isEqualTo("Saturday 5s");
		assertThat(result.home().label()).isEqualTo("Kwame’s side");
		assertThat(result.home().score()).isEqualTo(2);
		assertThat(result.home().players()).extracting(ShareStats.Player::name).containsExactly("Kwame", "Yaw");
		assertThat(result.away().label()).isEqualTo("Other side");
		assertThat(result.away().players()).singleElement().satisfies(p -> {
			assertThat(p.name()).isEqualTo("Ama");
			assertThat(p.goals()).isEqualTo(2);
		});

		var notPlayed = games.create(new GameDetails("football", "5-a-side", "Next week", NOW.plus(Duration.ofDays(6)), 60, "unlisted", null,
				null, "Legon Park", null, null, 10, 0, null, "public", null), kwame).id();
		assertThatThrownBy(() -> share.result(owner, notPlayed)).hasMessage("That game has no result to share.");
	}

	@Test
	void theWeekAndTheTotalsAreRealNumbers() {
		drawYesterday();
		var recap = share.recap(owner, 7);
		assertThat(recap.games()).isEqualTo(1);
		assertThat(recap.players()).as("Kwame, Ama and the guest").isEqualTo(3);
		assertThat(recap.goals()).isEqualTo(4);
		assertThat(recap.results()).hasSize(1);
		assertThat(recap.topScorers()).extracting(ShareStats.Scorer::name).containsExactlyInAnyOrder("Kwame", "Ama");

		var milestone = share.milestone(owner);
		assertThat(milestone.members()).isEqualTo(3);
		assertThat(milestone.gamesPlayed()).isEqualTo(1);
		assertThat(milestone.sports()).isEqualTo(1);

		games.create(new GameDetails("football", "5-a-side", "Tuesday 5s", NOW.plus(Duration.ofDays(2)), 60, "unlisted", null, null,
				"Legon Park", "Legon", null, 10, 0, null, "public", null), kwame);
		assertThat(share.upcoming(owner, 7)).singleElement().satisfies(g -> {
			assertThat(g.title()).isEqualTo("Tuesday 5s");
			assertThat(g.spotsLeft()).isEqualTo(9);
		});
	}

	@Test
	void onlyStaff() {
		var game = drawYesterday();
		assertThatThrownBy(() -> share.result(kwame, game)).hasMessage("Not found.");
		assertThatThrownBy(() -> share.recap(kwame, 7)).hasMessage("Not found.");
		assertThatThrownBy(() -> share.milestone(kwame)).hasMessage("Not found.");
		assertThatThrownBy(() -> share.upcoming(kwame, 7)).hasMessage("Not found.");
	}

}
