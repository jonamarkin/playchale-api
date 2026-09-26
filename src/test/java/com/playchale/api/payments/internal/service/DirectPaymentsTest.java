package com.playchale.api.payments.internal.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.games.internal.domain.GameDetails;
import com.playchale.api.games.internal.service.GameService;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.users.internal.service.ProfileChanges;
import com.playchale.api.users.internal.service.UserProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** In-app payments switched off: players pay the host directly, and the host marks them paid. */
@SpringBootTest(properties = "playchale.payments.in-app=false")
@Import(TestcontainersConfiguration.class)
class DirectPaymentsTest {

	@Autowired
	PaymentService payments;

	@Autowired
	GameService games;

	@Autowired
	UserDirectory users;

	@Autowired
	UserProfileService profiles;

	@Autowired
	JdbcClient jdbc;

	UUID host;

	UUID kojo;

	UUID game;

	@BeforeEach
	void setUp() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, games, game_participants, notifications, payments, movements CASCADE").update();
		host = users.registerOrFind("+233244555123", "GH").id();
		profiles.update(host, new ProfileChanges(null, null, null, null, null, "020 123 4567", null));
		game = games.create(new GameDetails("football", "5-a-side", "Saturday 5s", Instant.now().plus(Duration.ofDays(1)), 60, "unlisted", null,
				null, "Legon Park", null, 10, 25_000, "public", null), host).id();
		kojo = users.registerOrFind("+233244555124", "GH").id();
		games.join(game, kojo);
	}

	@Test
	void playersPayTheHostDirectly() {
		assertThat(payments.inApp()).isFalse();
		assertThatThrownBy(() -> payments.start(game, "momo-mtn", "024 455 5124", kojo))
			.hasMessage("Paying in the app isn’t switched on yet. Pay the host directly, and they’ll mark you as paid.");

		assertThat(games.get(game, kojo).hostPayoutPhone()).as("where to send it").isEqualTo("+233201234567");
		var stranger = users.registerOrFind("+233244555125", "GH").id();
		assertThat(games.get(game, stranger).hostPayoutPhone()).as("only for players in the game").isNull();
		assertThat(games.get(game, null).hostPayoutPhone()).isNull();

		games.markPaidCash(game, kojo.toString(), host);
		assertThat(payments.statement(kojo)).singleElement().satisfies(line -> assertThat(line.method()).isEqualTo("cash"));
		assertThat(List.of(games.get(game, host).participants().get(1).paid())).containsExactly(true);
	}

}
