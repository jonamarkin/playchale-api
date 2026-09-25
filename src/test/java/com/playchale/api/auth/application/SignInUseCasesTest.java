package com.playchale.api.auth;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.web.AppException;
import com.playchale.api.web.ErrorCode;
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

	/** Remembers the last code instead of texting it. */
	static class Capture implements CodeSender {

		String phone;

		String code;

		@Override
		public void send(String phone, String code) {
			this.phone = phone;
			this.code = code;
		}

	}

	@TestConfiguration
	static class Fakes {

		@Bean
		@Primary
		Capture capture() {
			return new Capture();
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
	Capture sent;

	@Autowired
	TestClock clock;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void emptyTables() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions CASCADE").update();
	}

	private static void assertAppError(Runnable action, ErrorCode code, String message) {
		assertThatThrownBy(action::run)
			.isInstanceOfSatisfying(AppException.class, e -> {
				assertThat(e.code()).isEqualTo(code);
				if (message != null) {
					assertThat(e.getMessage()).isEqualTo(message);
				}
			});
	}

	@Test
	void firstSignInCreatesTheAccountAndLaterOnesReuseIt() {
		assertThat(auth.requestCode("024 455 5123")).as("no code handed back outside demo mode").isEmpty();
		assertThat(sent.phone).isEqualTo("+233244555123");
		assertThat(sent.code).matches("\\d{6}");

		var first = auth.verify("0244555123", sent.code);
		assertThat(first.user().getPhone()).isEqualTo("+233244555123");
		assertThat(first.user().getCountry()).isEqualTo("GH");
		assertThat(first.user().isOnboarded()).isFalse();
		assertThat(first.token()).isNotBlank();

		// The same code can't be used twice.
		assertAppError(() -> auth.verify("0244555123", sent.code), ErrorCode.INVALID, null);

		auth.requestCode("+233244555123");
		var second = auth.verify("024 455 5123", sent.code);
		assertThat(second.user().getId()).as("signing in again finds the same account").isEqualTo(first.user().getId());
	}

	@Test
	void fiveWrongGuessesLockTheCode() {
		auth.requestCode("024 455 5123");
		var wrong = sent.code.equals("000000") ? "111111" : "000000";

		for (int i = 0; i < AuthService.MAX_WRONG_GUESSES; i++) {
			assertAppError(() -> auth.verify("024 455 5123", wrong), ErrorCode.INVALID,
					"That code isn’t right. Check the SMS and try again.");
		}

		// The wrong guesses were counted, so even the right code is now refused.
		assertAppError(() -> auth.verify("024 455 5123", sent.code), ErrorCode.INVALID,
				"Too many wrong tries. Ask for a new code.");
	}

	@Test
	void codesExpire() {
		auth.requestCode("024 455 5123");
		clock.advance(AuthService.CODE_LIFETIME.plusSeconds(1));
		assertAppError(() -> auth.verify("024 455 5123", sent.code), ErrorCode.INVALID,
				"That code has expired. Ask for a new one.");
	}

	@Test
	void aNumberGetsFiveCodesAnHour() {
		for (int i = 0; i < AuthService.MAX_CODES_PER_HOUR; i++) {
			auth.requestCode("024 455 5123");
		}
		assertAppError(() -> auth.requestCode("024 455 5123"), ErrorCode.CONFLICT, null);
	}

	@Test
	void invalidNumbersAreRefused() {
		assertAppError(() -> auth.requestCode("12345"), ErrorCode.INVALID,
				"Enter a valid Ghana mobile number, e.g. 024 123 4567.");
	}

	@Test
	void sessionsLastUntilSignOut() {
		auth.requestCode("024 455 5123");
		var signedIn = auth.verify("024 455 5123", sent.code);

		assertThat(auth.currentUser(signedIn.token())).get().extracting(u -> u.getId()).isEqualTo(signedIn.user().getId());
		assertThat(auth.currentUser("not-a-real-token")).isEmpty();

		auth.signOut(signedIn.token());
		assertThat(auth.currentUser(signedIn.token())).isEmpty();
	}

}
