package com.playchale.api.payments.internal.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.games.internal.domain.GameDetails;
import com.playchale.api.games.internal.service.GameService;
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

/** The worker that chases pending payments, against a real Postgres with a clock we move. */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, PaymentChecksTest.Clocks.class })
class PaymentChecksTest {

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
	PaymentChecks checks;

	@Autowired
	PaymentService payments;

	@Autowired
	GameService games;

	@Autowired
	UserDirectory users;

	@Autowired
	TestClock clock;

	@Autowired
	JdbcClient jdbc;

	UUID host;

	UUID game;

	@BeforeEach
	void setUp() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, games, game_participants, notifications, payments, movements CASCADE").update();
		clock.set(NOW);
		host = users.registerOrFind("+233244555123", "GH").id();
		game = games.create(new GameDetails("football", "5-a-side", "Saturday 5s", NOW.plus(Duration.ofDays(1)), 60, "unlisted", null, null,
				"Legon Park", null, 40, 25_000, "public", null), host).id();
	}

	/** A player who joins and starts paying, then closes the app. */
	private UUID pendingPayment(String phone) {
		var player = users.registerOrFind(phone, "GH").id();
		games.join(game, player);
		return payments.start(game, "momo-mtn", phone, player).id();
	}

	private String statusOf(UUID payment) {
		return jdbc.sql("SELECT status FROM payments WHERE id = :id").param("id", payment).query(String.class).single();
	}

	@Test
	void aPaymentThePayerStoppedWatchingIsStillSettled() {
		var payment = pendingPayment("+233244555124");
		checks.checkDuePayments();
		assertThat(statusOf(payment)).as("the app is still polling for the first minute").isEqualTo("pending");

		clock.advance(Duration.ofMinutes(2));
		checks.checkDuePayments();
		assertThat(statusOf(payment)).isEqualTo("succeeded");
		assertThat(jdbc.sql("SELECT count(*) FROM movements").query(Integer.class).single()).isEqualTo(2);
	}

	@Test
	void checksBackOffAndStopAfterADay() {
		var payment = pendingPayment("+233244555125");
		clock.advance(Duration.ofMinutes(2));
		assertThat(checks.claimDue()).containsExactly(payment);
		assertThat(checks.claimDue()).as("not due again straight away").isEmpty();

		jdbc.sql("UPDATE payments SET checks = :max WHERE id = :id").param("max", PaymentChecks.MAX_CHECKS).param("id", payment).update();
		clock.advance(Duration.ofHours(2));
		assertThat(checks.claimDue()).as("given up on: left for a person").isEmpty();
	}

	@Test
	void twoWorkersNeverClaimTheSamePayment() throws Exception {
		var pending = new ArrayList<UUID>();
		for (int i = 0; i < 30; i++) {
			pending.add(pendingPayment("+2332445552%02d".formatted(i)));
		}
		clock.advance(Duration.ofMinutes(2));

		var start = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var first = pool.submit(() -> {
				start.await();
				return checks.claimDue();
			});
			var second = pool.submit(() -> {
				start.await();
				return checks.claimDue();
			});
			start.countDown();
			List<UUID> a = first.get();
			List<UUID> b = second.get();
			var overlap = new HashSet<>(a);
			overlap.retainAll(b);
			assertThat(overlap).isEmpty();
			assertThat(a.size() + b.size()).as("a batch is 20; together they take everything due").isEqualTo(30);
		}
	}

}
