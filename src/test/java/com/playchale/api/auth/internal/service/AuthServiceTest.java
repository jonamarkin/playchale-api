package com.playchale.api.auth.internal.service;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.auth.internal.domain.SignInCode;
import com.playchale.api.integration.sms.SmsSender;
import com.playchale.api.shared.TestClock;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.shared.error.ErrorCode;
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

/** Sign-in against a real Postgres, with random codes (demo mode off) and a clock we control. */
@SpringBootTest(properties = "playchale.demo-sign-in-code=")
@Import({ TestcontainersConfiguration.class, AuthServiceTest.Fakes.class })
class AuthServiceTest {

	/** Keeps the last text instead of sending it, so the test can read the code. */
	static class Inbox implements SmsSender {

		String phone;

		String message;

		@Override
		public void send(String phone, String message) {
			this.phone = phone;
			this.message = message;
		}

		String code() {
			return message.replaceAll("\\D*(\\d{6}).*", "$1");
		}

	}

	@TestConfiguration
	static class Fakes {

		@Bean
		@Primary
		Inbox inbox() {
			return new Inbox();
		}

		@Bean
		@Primary
		TestClock testClock() {
			return new TestClock();
		}

	}

	@Autowired
	AuthService auth;

	@Autowired
	Inbox sms;

	@Autowired
	TestClock clock;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void emptyTables() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions CASCADE").update();
	}

	private static void assertRefused(Runnable action, ErrorCode code, String message) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, e -> {
			assertThat(e.code()).isEqualTo(code);
			if (message != null) {
				assertThat(e.getMessage()).isEqualTo(message);
			}
		});
	}

	@Test
	void firstSignInRegistersThePlayerAndLaterOnesFindThem() {
		assertThat(auth.requestCode("024 455 5123")).as("no code handed back outside demo mode").isEmpty();
		assertThat(sms.phone).isEqualTo("+233244555123");
		assertThat(sms.code()).matches("\\d{6}");

		var first = auth.signIn("0244555123", sms.code());
		assertThat(first.user().phone()).isEqualTo("+233244555123");
		assertThat(first.user().onboarded()).isFalse();
		assertThat(first.token()).isNotBlank();

		// The same code can't be used twice.
		assertRefused(() -> auth.signIn("0244555123", sms.code()), ErrorCode.INVALID, null);

		auth.requestCode("+233244555123");
		var second = auth.signIn("024 455 5123", sms.code());
		assertThat(second.user().id()).as("signing in again finds the same player").isEqualTo(first.user().id());
	}

	@Test
	void wrongGuessesAreSavedEvenThoughTheRequestFails() {
		auth.requestCode("024 455 5123");
		var wrong = sms.code().equals("000000") ? "111111" : "000000";

		for (int i = 0; i < SignInCode.MAX_WRONG_GUESSES; i++) {
			assertRefused(() -> auth.signIn("024 455 5123", wrong), ErrorCode.INVALID, "That code isn’t right. Check the SMS and try again.");
		}

		// Had the refusals rolled back, the count would still be 0 and the right code would work.
		assertRefused(() -> auth.signIn("024 455 5123", sms.code()), ErrorCode.INVALID, "Too many wrong tries. Ask for a new code.");
	}

	@Test
	void codesExpire() {
		auth.requestCode("024 455 5123");
		clock.advance(SignInCode.LIFETIME.plusSeconds(1));
		assertRefused(() -> auth.signIn("024 455 5123", sms.code()), ErrorCode.INVALID, "That code has expired. Ask for a new one.");
	}

	@Test
	void aNumberGetsFiveCodesAnHour() {
		for (int i = 0; i < SignInCode.MAX_PER_HOUR; i++) {
			auth.requestCode("024 455 5123");
		}
		assertRefused(() -> auth.requestCode("024 455 5123"), ErrorCode.CONFLICT, null);
	}

	@Test
	void invalidNumbersAreRefused() {
		assertRefused(() -> auth.requestCode("12345"), ErrorCode.INVALID, "Enter a valid Ghana mobile number, e.g. 024 123 4567.");
	}

	@Test
	void sessionsLastUntilSignOut() {
		auth.requestCode("024 455 5123");
		var signedIn = auth.signIn("024 455 5123", sms.code());

		assertThat(auth.userIdFor(signedIn.token())).contains(signedIn.user().id());
		assertThat(auth.userIdFor("not-a-real-token")).isEmpty();

		auth.signOut(signedIn.token());
		assertThat(auth.userIdFor(signedIn.token())).isEmpty();
	}

}
