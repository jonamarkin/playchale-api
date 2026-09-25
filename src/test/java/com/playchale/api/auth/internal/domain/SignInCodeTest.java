package com.playchale.api.auth.internal.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.playchale.api.auth.internal.domain.SignInCode.Attempt;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The rules for using a sign-in code. A plain unit test: no Spring, no database. */
class SignInCodeTest {

	private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");

	private static final byte[] RIGHT = "right".getBytes(StandardCharsets.UTF_8);

	private static final byte[] WRONG = "wrong".getBytes(StandardCharsets.UTF_8);

	private final SignInCode code = new SignInCode("sms", "+233244555123", RIGHT, NOW);

	@Test
	void theRightCodeIsAcceptedOnceAndUsedUp() {
		assertThat(code.isLive(NOW)).isTrue();
		assertThat(code.attempt(RIGHT, NOW)).isEqualTo(Attempt.ACCEPTED);
		assertThat(code.isLive(NOW)).as("used codes aren't live").isFalse();
	}

	@Test
	void wrongGuessesCountAndTheFifthLocksTheCode() {
		for (int i = 1; i <= SignInCode.MAX_WRONG_GUESSES; i++) {
			assertThat(code.attempt(WRONG, NOW)).isEqualTo(Attempt.WRONG);
			assertThat(code.getAttempts()).isEqualTo(i);
		}
		assertThat(code.attempt(RIGHT, NOW)).as("even the right code, once locked").isEqualTo(Attempt.LOCKED);
	}

	@Test
	void codesExpireAfterTheirLifetime() {
		assertThat(code.isLive(NOW.plus(SignInCode.LIFETIME).minusSeconds(1))).isTrue();
		assertThat(code.isLive(NOW.plus(SignInCode.LIFETIME))).isFalse();
	}

}
