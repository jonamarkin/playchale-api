package com.playchale.api.payments.internal.service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.games.internal.domain.GameDetails;
import com.playchale.api.games.internal.service.GameResponse;
import com.playchale.api.games.internal.service.GameService;
import com.playchale.api.notifications.internal.service.NotificationService;
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

/** Paying shares against a real Postgres, with the simulated provider and a clock we move. */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, PaymentServiceTest.Clocks.class })
class PaymentServiceTest {

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
	PaymentService payments;

	@Autowired
	GameService games;

	@Autowired
	NotificationService notifications;

	@Autowired
	UserDirectory users;

	@Autowired
	TestClock clock;

	@Autowired
	JdbcClient jdbc;

	UUID kwame;

	UUID kojo;

	UUID ama;

	GameResponse game;

	@BeforeEach
	void setUp() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, games, game_participants, notifications, payments, movements CASCADE").update();
		clock.set(NOW);
		kwame = user("+233244555123", "Kwame Mensah");
		kojo = user("+233244555124", "Kojo Owusu");
		ama = user("+233244555125", "Ama Serwaa");
		game = games.create(new GameDetails("football", "5-a-side", "Saturday 5s", NOW.plus(Duration.ofDays(1)), 60, "unlisted", null,
				null, "Legon Park", null, 10, 25_000, "public", null), kwame);
		games.join(game.id(), kojo);
	}

	private UUID user(String phone, String name) {
		var id = users.registerOrFind(phone, "GH").id();
		jdbc.sql("UPDATE users SET name = :name WHERE id = :id").param("name", name).param("id", id).update();
		return id;
	}

	@Test
	void aMobileMoneyPaymentIsPendingUntilApprovedThenTheShareIsPaid() {
		var started = payments.start(game.id(), "momo-mtn", "024 455 5124", kojo);
		assertThat(started.status()).isEqualTo("pending");
		assertThat(started.amount()).isEqualTo(2_500);
		assertThat(started.payerPhone()).isEqualTo("+233244555124");
		assertThat(started.reference()).startsWith("PC-");

		assertThat(payments.status(started.id(), kojo).status()).as("still being approved").isEqualTo("pending");
		clock.advance(Duration.ofSeconds(3));
		var settled = payments.status(started.id(), kojo);
		assertThat(settled.status()).isEqualTo("succeeded");

		var spot = games.get(game.id(), kojo).participants().get(1);
		assertThat(spot.paid()).isTrue();
		assertThat(spot.paidVia()).isEqualTo("app");
		assertThat(spot.paymentId()).isEqualTo(started.id());

		assertThat(payments.statement(kojo)).singleElement().satisfies(line -> {
			assertThat(line.direction()).isEqualTo("out");
			assertThat(line.counterparty().name()).isEqualTo("Kwame Mensah");
			assertThat(line.gameTitle()).isEqualTo("Saturday 5s");
		});
		assertThat(payments.statement(kwame)).singleElement().satisfies(line -> assertThat(line.direction()).isEqualTo("in"));
		assertThat(notifications.list(kwame).getFirst().title()).isEqualTo("Kojo paid GH₵ 25");
		assertThat(notifications.list(kwame).getFirst().body()).isEqualTo("Saturday 5s · 1 of 2 paid");

		// Checking again changes nothing, and paying twice is refused.
		payments.status(started.id(), kojo);
		assertThat(payments.statement(kojo)).hasSize(1);
		assertThatThrownBy(() -> payments.start(game.id(), "card", null, kojo)).hasMessage("You’ve already paid your share.");
	}

	@Test
	void aDeclinedPaymentTakesNothingAndCanBeTriedAgain() {
		var declined = payments.start(game.id(), "momo-telecel", "020 123 4000", kojo);
		clock.advance(Duration.ofSeconds(3));
		var failed = payments.status(declined.id(), kojo);
		assertThat(failed.status()).isEqualTo("failed");
		assertThat(failed.failureReason()).isEqualTo("The payment was declined on the phone. No money was taken.");
		assertThat(payments.statement(kojo)).isEmpty();

		var card = payments.start(game.id(), "card", "024 455 5124", kojo);
		assertThat(card.payerPhone()).as("cards aren't charged to a number").isNull();
		clock.advance(Duration.ofSeconds(3));
		assertThat(payments.status(card.id(), kojo).status()).isEqualTo("succeeded");
	}

	@Test
	void onlyPlayersInThePaidGameCanPay() {
		assertThatThrownBy(() -> payments.start(game.id(), "card", null, ama)).hasMessage("Join the game before paying your share.");
		assertThatThrownBy(() -> payments.start(game.id(), "momo-mtn", null, kojo)).hasMessage("Enter the mobile money number to charge.");
		assertThatThrownBy(() -> payments.start(game.id(), "momo-mtn", "12345", kojo)).hasMessage("Enter the mobile money number to charge.");
		assertThatThrownBy(() -> payments.start(game.id(), "bitcoin", null, kojo)).hasMessage("Pick how you’ll pay.");

		var free = games.create(new GameDetails("football", "5-a-side", null, NOW.plus(Duration.ofDays(1)), 60, "unlisted", null, null,
				"Legon", null, 10, 0, "public", null), kwame);
		assertThatThrownBy(() -> payments.start(free.id(), "card", null, kwame)).hasMessage("This game is free. There’s nothing to pay.");

		var mine = payments.start(game.id(), "card", null, kojo);
		assertThat(payments.get(mine.id(), ama)).as("nobody sees someone else's payment").isEmpty();
		assertThatThrownBy(() -> payments.status(mine.id(), ama)).hasMessage("We couldn’t find that payment.");
	}

	@Test
	void cashHandedToTheHostIsOnBothStatements() {
		games.markPaidCash(game.id(), kojo.toString(), kwame);
		assertThat(payments.statement(kojo)).singleElement().satisfies(line -> {
			assertThat(line.method()).isEqualTo("cash");
			assertThat(line.direction()).isEqualTo("out");
		});
		var guest = games.addGuest(game.id(), "Kofi", null, kwame).game().players().getLast().id();
		games.markPaidCash(game.id(), guest, kwame);
		assertThat(payments.statement(kwame)).hasSize(2).allSatisfy(line -> assertThat(line.direction()).isEqualTo("in"));
		assertThat(payments.statement(kwame).getFirst().counterparty()).as("a guest has no account").isNull();
	}

}
