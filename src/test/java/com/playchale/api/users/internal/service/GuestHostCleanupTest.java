package com.playchale.api.users.internal.service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.users.api.Terms;
import com.playchale.api.users.api.UserDirectory;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guest hosts nobody needs any more are retired: one who never hosted, two days on, and one whose
 * last game was 90 days ago. One with a game still to come, or a recent one, stays.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GuestHostCleanupTest {

	private static final String PRIVATE_GAME = """
			{"sport":"football","format":"5-a-side","startsAt":"2030-06-02T16:00:00.000Z","durationMinutes":60,
			 "venue":{"kind":"unlisted","name":"Legon Park","area":"Legon"},"capacity":10,"totalCost":0,"visibility":"private"}
			""";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	UserDirectory directory;

	@Autowired
	GuestHostCleanup cleanup;

	@BeforeEach
	void emptyTables() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, games, game_participants, notifications, rate_limits, host_keys CASCADE").update();
	}

	@Test
	void aGuestWhoNeverHostedGoesAfterTwoDays() {
		var old = directory.registerGuestHost("Ekow Brown", "+233244555701", "GH", Terms.CURRENT).id();
		var fresh = directory.registerGuestHost("Ama Mensah", "+233244555702", "GH", Terms.CURRENT).id();
		jdbc.sql("UPDATE users SET created_at = :at WHERE id = :id").param("at", ago(Duration.ofDays(3))).param("id", old).update();

		cleanup.tidy();

		assertThat(retired(old)).isTrue();
		assertThat(retired(fresh)).isFalse();
		// Their number goes with them, so it can host (or sign up) afresh.
		assertThat(jdbc.sql("SELECT count(*) FROM users WHERE guest_phone = '+233244555701' AND deleted_at IS NULL").query(Integer.class).single()).isZero();
	}

	@Test
	void aGuestWhoseLastGameWasNinetyDaysAgoGoesAndTheGameStays() throws Exception {
		var done = hostAGame("Kojo Asare", "024 455 5703");
		var playing = hostAGame("Esi Owusu", "024 455 5704");
		jdbc.sql("UPDATE games SET starts_at = :at WHERE host_id = :id").param("at", ago(Duration.ofDays(100))).param("id", done).update();
		jdbc.sql("UPDATE users SET created_at = :at WHERE id IN (:ids)").param("at", ago(Duration.ofDays(120)))
			.param("ids", List.of(done, playing)).update();

		cleanup.tidy();

		assertThat(retired(done)).isTrue();
		assertThat(retired(playing)).as("a game still to come").isFalse();
		assertThat(jdbc.sql("SELECT count(*) FROM games WHERE host_id = :id").param("id", done).query(Integer.class).single())
			.as("old line-ups still add up").isEqualTo(1);
	}

	private UUID hostAGame(String name, String phone) throws Exception {
		var started = mvc.perform(post("/auth/guest-hosts").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"%s\",\"phone\":\"%s\",\"country\":\"GH\",\"termsVersion\":\"%s\"}".formatted(name, phone, Terms.CURRENT)))
			.andExpect(status().isCreated()).andReturn().getResponse();
		var cookie = new Cookie("playchale_session", started.getHeader("Set-Cookie").split(";")[0].split("=", 2)[1]);
		mvc.perform(post("/games").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(PRIVATE_GAME)).andExpect(status().isCreated());
		return jdbc.sql("SELECT id FROM users WHERE guest_phone = :phone").param("phone", "+233" + phone.replace(" ", "").substring(1))
			.query(UUID.class).single();
	}

	private boolean retired(UUID id) {
		return jdbc.sql("SELECT deleted_at IS NOT NULL FROM users WHERE id = :id").param("id", id).query(Boolean.class).single();
	}

	private static OffsetDateTime ago(Duration duration) {
		return Instant.now().minus(duration).atOffset(ZoneOffset.UTC);
	}

}
