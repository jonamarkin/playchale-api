package com.playchale.api.admin.internal.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.games.internal.domain.GameDetails;
import com.playchale.api.games.internal.service.GameService;
import com.playchale.api.games.internal.service.GameTalk;
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
 * The admin desk, and mostly the gate in front of it: an admin account can read every phone number
 * in the country, so who may open one is the whole security question.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, AdminDeskTest.Clocks.class })
class AdminDeskTest {

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
	AdminDesk desk;

	@Autowired
	Staff staff;

	@Autowired
	GameService games;

	@Autowired
	GameTalk talk;

	@Autowired
	UserDirectory users;

	@Autowired
	TestClock clock;

	@Autowired
	JdbcClient jdbc;

	UUID owner;

	UUID support;

	UUID player;

	@BeforeEach
	void setUp() {
		jdbc.sql("""
				TRUNCATE users, sign_in_codes, sessions, games, game_participants, game_messages, notifications, game_results,
				audit_events, platform_staff CASCADE
				""").update();
		clock.set(NOW);
		owner = user("+233244555123", "Ama Owner");
		support = user("+233244555124", "Sam Support");
		player = user("+233244555125", "Kojo Player");
		// The first row is an INSERT by hand on the server; nothing in the app can make one.
		jdbc.sql("INSERT INTO platform_staff (user_id, role) VALUES (:id, 'owner')").param("id", owner).update();
	}

	private UUID user(String phone, String name) {
		var id = users.registerOrFind(phone, "GH").id();
		jdbc.sql("UPDATE users SET name = :name WHERE id = :id").param("name", name).param("id", id).update();
		return id;
	}

	@Test
	void someoneWhoDoesNotWorkHereIsToldNothingExists() {
		// Not "forbidden": an admin area should not confirm itself to someone trying the door.
		assertThatThrownBy(() -> desk.find(player, "", 10)).hasMessage("Not found.");
		assertThatThrownBy(() -> desk.history(player, owner, 10)).hasMessage("Not found.");
		assertThatThrownBy(() -> desk.health(player, 30)).hasMessage("Not found.");
		assertThatThrownBy(() -> desk.export(player, owner)).hasMessage("Not found.");
		assertThatThrownBy(() -> desk.messages(player, 10)).hasMessage("Not found.");
	}

	@Test
	void onlyAnOwnerDecidesWhoWorksHere() {
		staff.add(owner, support, Staff.SUPPORT, "the first support hire");
		assertThat(staff.roleOf(support)).contains(Staff.SUPPORT);

		// Support can do the job, but cannot hand out the keys.
		assertThat(desk.find(support, "", 10)).isNotEmpty();
		assertThatThrownBy(() -> staff.add(support, player, Staff.SUPPORT, null))
			.hasMessage("Only an owner can change who works here.");
		assertThatThrownBy(() -> staff.remove(support, owner)).hasMessage("Only an owner can change who works here.");
	}

	@Test
	void anOwnerCannotLockEveryoneOutByRemovingThemselves() {
		assertThatThrownBy(() -> staff.remove(owner, owner)).hasMessage("You can’t remove yourself. Ask another owner.");
	}

	@Test
	void aClosedAccountIsNotStaffWhateverTheTableSays() {
		staff.add(owner, support, Staff.SUPPORT, null);
		jdbc.sql("UPDATE users SET deleted_at = now() WHERE id = :id").param("id", support).update();
		assertThat(staff.isStaff(support)).isFalse();
		assertThatThrownBy(() -> desk.find(support, "", 10)).hasMessage("Not found.");
	}

	@Test
	void peopleCanBeFoundByAnythingTheyAreKnownBy() {
		assertThat(desk.find(owner, "Kojo", 10)).singleElement().satisfies(p -> assertThat(p.name()).isEqualTo("Kojo Player"));
		// The way a number is written in Ghana, and the way it is written on the wire, both find them.
		assertThat(desk.find(owner, "0244555125", 10)).hasSize(1);
		assertThat(desk.find(owner, "024 455 5125", 10)).hasSize(1);
		assertThat(desk.find(owner, "+233244555125", 10)).hasSize(1);
		assertThat(desk.find(owner, "1", 10)).as("a single digit is not a phone search").isEmpty();
		assertThat(desk.find(owner, "", 10)).as("a blank search shows the newest, not nothing").hasSize(3);
	}

	@Test
	void lookingSomeoneUpIsItselfRecorded() {
		desk.history(owner, player, 20);
		var looked = jdbc.sql("SELECT count(*) FROM audit_events WHERE event_type = 'admin.viewed-person' AND actor_id = :id")
			.param("id", owner).query(Long.class).single();
		assertThat(looked).as("who looked at whom, with their name on it").isOne();
	}

	@Test
	void whatSomeoneSaidIsPartOfWhatHappenedToThem() {
		var game = games.create(new GameDetails("football", "5-a-side", "Saturday 5s", NOW.plus(Duration.ofHours(4)), 60, "unlisted", null,
				null, "Legon Park", "Legon", null, 10, 0, null, "public", null), player);
		talk.say(game.id(), "cheap jerseys, call me", player);
		assertThat(desk.history(owner, player, 20)).extracting(AdminDesk.Entry::what).contains("Said in Saturday 5s");
	}

	@Test
	void aMessageNobodyInTheGameWillRemoveCanBeTakenDown() {
		var game = games.create(new GameDetails("football", "5-a-side", "Saturday 5s", NOW.plus(Duration.ofHours(4)), 60, "unlisted", null,
				null, "Legon Park", "Legon", null, 10, 0, null, "public", null), player);
		var said = talk.say(game.id(), "Something out of order", player).get(0);

		assertThat(desk.messages(owner, 10)).singleElement().satisfies(m -> assertThat(m.body()).isEqualTo("Something out of order"));
		desk.removeMessage(owner, said.id(), "abuse");
		assertThat(desk.messages(owner, 10)).isEmpty();

		var why = jdbc.sql("SELECT details ->> 'why' FROM audit_events WHERE event_type = 'admin.removed-message'")
			.query(String.class).single();
		assertThat(why).isEqualTo("abuse");
	}

	@Test
	void theHealthNumbersCountWhatHappened() {
		var game = games.create(new GameDetails("football", "5-a-side", "Saturday 5s", NOW.plus(Duration.ofHours(4)), 60, "unlisted", null, null,
				"Legon Park", "Legon", null, 10, 0, null, "public", null), player);
		games.join(game.id(), support);
		games.leave(game.id(), support);
		var health = desk.health(owner, 30);
		assertThat(health.signups()).isEqualTo(3);
		assertThat(health.gamesCreated()).isOne();
		assertThat(health.gamesPlayed()).isZero();
		// The host's own spot, and Sam's join — which still happened, though he later left.
		assertThat(health.joins()).isEqualTo(2);
	}

	@Test
	void anExportHoldsEverythingAboutOnePersonAndNobodyElse() {
		var game = games.create(new GameDetails("football", "5-a-side", "Saturday 5s", NOW.plus(Duration.ofHours(4)), 60, "unlisted", null,
				null, "Legon Park", "Legon", null, 10, 0, null, "public", null), player);
		talk.say(game.id(), "On my way", player);

		var export = desk.export(owner, player);
		assertThat(export).containsKeys("user", "games", "spots", "messages", "payments", "movements", "notifications");
		assertThat((List<?>) export.get("messages")).hasSize(1);
		assertThat((List<?>) desk.export(owner, support).get("messages")).as("and only theirs").isEmpty();
	}

}
