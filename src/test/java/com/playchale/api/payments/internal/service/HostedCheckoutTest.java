package com.playchale.api.payments.internal.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.games.internal.domain.GameDetails;
import com.playchale.api.games.internal.service.GameService;
import com.playchale.api.integration.payments.PaymentProvider;
import com.playchale.api.shared.TestClock;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.users.internal.service.ProfileChanges;
import com.playchale.api.users.internal.service.UserProfileService;
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
 * Paying through a hosted checkout (Paystack's kind), with a stand-in provider: the email it needs,
 * the link it hands back, and webhooks that only prompt a check and only count once.
 */
@SpringBootTest(properties = "playchale.payments.web-app-url=https://playchale.com")
@Import({ TestcontainersConfiguration.class, HostedCheckoutTest.Fakes.class })
class HostedCheckoutTest {

	/** Behaves like Paystack's checkout: needs an email, gives a link, answers "success" once told to. */
	static class HostedProvider implements PaymentProvider {

		PaymentProvider.Status answer = Status.pending();

		/** When set, asking fails the way an outage or a timeout would. */
		boolean down;

		Charge lastCharge;

		@Override
		public boolean needsPayerPhone() {
			return false;
		}

		@Override
		public boolean needsEmail() {
			return true;
		}

		@Override
		public Started charge(Charge charge) {
			lastCharge = charge;
			return new Started("https://checkout.example/" + charge.reference());
		}

		@Override
		public Status check(Charge charge, Instant startedAt) {
			if (down) {
				throw new IllegalStateException("connect timed out");
			}
			return answer;
		}

		@Override
		public Optional<String> webhookReference(String body, String signature) {
			return "genuine".equals(signature) ? Optional.of(body) : Optional.empty();
		}

	}

	@TestConfiguration
	static class Fakes {

		@Bean
		@Primary
		HostedProvider hostedProvider() {
			return new HostedProvider();
		}

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
	HostedProvider provider;

	@Autowired
	GameService games;

	@Autowired
	UserDirectory users;

	@Autowired
	UserProfileService profiles;

	@Autowired
	TestClock clock;

	@Autowired
	JdbcClient jdbc;

	UUID game;

	UUID kojo;

	@BeforeEach
	void setUp() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, games, game_participants, notifications, payments, movements, webhook_events CASCADE")
			.update();
		clock.set(NOW);
		provider.answer = PaymentProvider.Status.pending();
		provider.down = false;
		var host = users.registerOrFind("+233244555123", "GH").id();
		game = games.create(new GameDetails("football", "5-a-side", "Saturday 5s", NOW.plus(Duration.ofDays(1)), 60, "unlisted", null, null,
				"Legon Park", null, 10, 25_000, "public", null), host).id();
		kojo = users.registerOrFind("+233244555124", "GH").id();
		games.join(game, kojo);
	}

	private static ProfileChanges email(String email) {
		return new ProfileChanges(null, null, null, null, null, null, email);
	}

	@Test
	void theProvidersCheckoutNeedsAnEmailAndSendsThePayerBackToTheGame() {
		assertThatThrownBy(() -> payments.start(game, "momo-mtn", null, kojo))
			.hasMessage("Add your email address first. Paystack sends your receipt there.");

		profiles.update(kojo, email(" Kojo@Example.com "));
		var started = payments.start(game, "momo-mtn", null, kojo);
		assertThat(started.authorizationUrl()).isEqualTo("https://checkout.example/" + started.reference());
		assertThat(started.payerPhone()).as("given on the checkout page instead").isNull();
		assertThat(provider.lastCharge.email()).isEqualTo("kojo@example.com");
		assertThat(provider.lastCharge.returnUrl()).isEqualTo("https://playchale.com/games/%s?payment=%s".formatted(game, started.id()));
	}

	@Test
	void aSignedWebhookPromptsACheckAndCountsOnce() {
		profiles.update(kojo, email("kojo@example.com"));
		var started = payments.start(game, "card", null, kojo);

		assertThat(payments.handleWebhook("paystack", started.reference(), "forged")).isFalse();

		provider.answer = PaymentProvider.Status.succeeded();
		assertThat(payments.handleWebhook("paystack", started.reference(), "genuine")).isTrue();
		assertThat(payments.get(started.id(), kojo)).get().satisfies(p -> {
			assertThat(p.status()).isEqualTo("succeeded");
			assertThat(p.authorizationUrl()).as("nothing left to pay").isNull();
		});

		assertThat(payments.handleWebhook("paystack", started.reference(), "genuine")).as("a retried delivery").isTrue();
		assertThat(payments.statement(kojo)).as("one line, however often it's delivered").hasSize(1);
	}

	@Test
	void whenThePayersAppCantReachTheProviderThePaymentWaits() {
		profiles.update(kojo, email("kojo@example.com"));
		var started = payments.start(game, "card", null, kojo);
		provider.down = true;
		assertThat(payments.status(started.id(), kojo).status()).as("no error for the payer; asked again later").isEqualTo("pending");
		assertThat(payments.reconcile(started.id())).as("the worker keeps it on its list").isTrue();
	}

}
