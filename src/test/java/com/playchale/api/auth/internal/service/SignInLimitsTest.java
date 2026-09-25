package com.playchale.api.auth.internal.service;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.shared.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The caps on sign-in texts beyond the per-number one, set low here so they're quick to reach. */
@SpringBootTest(properties = { "playchale.sign-in.per-connection-per-hour=3", "playchale.sign-in.per-day=5" })
@Import(TestcontainersConfiguration.class)
class SignInLimitsTest {

	@Autowired
	AuthService auth;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void emptyTables() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, rate_limits CASCADE").update();
	}

	@Test
	void oneConnectionCantAskForCodesForNumberAfterNumber() {
		auth.requestCode("024 455 5121", null, "203.0.113.7");
		auth.requestCode("024 455 5122", null, "203.0.113.7");
		auth.requestCode("024 455 5123", null, "203.0.113.7");
		assertThatThrownBy(() -> auth.requestCode("024 455 5124", null, "203.0.113.7")).isInstanceOf(BusinessException.class)
			.hasMessage("Too many codes asked for from this connection. Try again in an hour.");

		// Someone else isn't held up by it.
		auth.requestCode("024 455 5124", null, "198.51.100.9");
	}

	@Test
	void theWholeServiceStopsAtItsDailyLimit() {
		for (int i = 0; i < 5; i++) {
			auth.requestCode("024 455 51%02d".formatted(i), null, "198.51.100." + i);
		}
		assertThatThrownBy(() -> auth.requestCode("024 455 5199", null, "198.51.100.99"))
			.hasMessage("We can’t send sign-in codes right now. Please try again later.");
	}

}
