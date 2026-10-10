package com.playchale.api.auth.internal.service;

import java.time.Duration;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.auth.internal.domain.SignInCode;
import com.playchale.api.integration.email.Email;
import com.playchale.api.integration.email.EmailSender;
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

	/** Keeps the last email instead of sending it, or fails like an email provider that's down. */
	static class Mailbox implements EmailSender {

		Email last;

		boolean down;

		/** The codes saved when the email went out: proof it went out after they were committed. */
		long savedWhenSent = -1;

		JdbcClient jdbc;

		@Override
		public void send(Email email) {
			if (down) {
				throw BusinessException.conflict("We couldn’t send the email just now. Please try again in a minute.");
			}
			if (jdbc != null) {
				savedWhenSent = jdbc.sql("SELECT count(*) FROM sign_in_codes").query(Long.class).single();
			}
			this.last = email;
		}

		String code() {
			return last.subject().replaceAll("\\D*(\\d{6}).*", "$1");
		}

	}

	@TestConfiguration
	static class Fakes {

		@Bean
		@Primary
		Mailbox mailbox() {
			return new Mailbox();
		}

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

	/** Where the test's requests come from (a documentation address). */
	private static final String CONNECTION = "203.0.113.7";

	@Autowired
	AuthService auth;

	@Autowired
	Inbox sms;

	@Autowired
	Mailbox mail;

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
		assertThat(auth.requestCode("024 455 5123", null, CONNECTION)).as("no code handed back outside demo mode").isEmpty();
		assertThat(sms.phone).isEqualTo("+233244555123");
		assertThat(sms.code()).matches("\\d{6}");
		// One SMS, not two: plain characters, so a part holds 160 of them rather than 70.
		assertThat(sms.message).matches("[\\x20-\\x7E]{1,160}");

		var first = auth.signIn("0244555123", null, sms.code());
		assertThat(first.user().phone()).isEqualTo("+233244555123");
		assertThat(first.user().onboarded()).isFalse();
		assertThat(first.token()).isNotBlank();

		// The same code can't be used twice.
		assertRefused(() -> auth.signIn("0244555123", null, sms.code()), ErrorCode.INVALID, null);

		auth.requestCode("+233244555123", null, CONNECTION);
		var second = auth.signIn("024 455 5123", null, sms.code());
		assertThat(second.user().id()).as("signing in again finds the same player").isEqualTo(first.user().id());
	}

	@Test
	void wrongGuessesAreSavedEvenThoughTheRequestFails() {
		auth.requestCode("024 455 5123", null, CONNECTION);
		var wrong = sms.code().equals("000000") ? "111111" : "000000";

		for (int i = 0; i < SignInCode.MAX_WRONG_GUESSES; i++) {
			assertRefused(() -> auth.signIn("024 455 5123", null, wrong), ErrorCode.INVALID, "That code isn’t right. Check the SMS and try again.");
		}

		// Had the refusals rolled back, the count would still be 0 and the right code would work.
		assertRefused(() -> auth.signIn("024 455 5123", null, sms.code()), ErrorCode.INVALID, "Too many wrong tries. Ask for a new code.");
	}

	@Test
	void codesExpire() {
		auth.requestCode("024 455 5123", null, CONNECTION);
		clock.advance(SignInCode.LIFETIME.plusSeconds(1));
		assertRefused(() -> auth.signIn("024 455 5123", null, sms.code()), ErrorCode.INVALID, "That code has expired. Ask for a new one.");
	}

	@Test
	void theEmailGoesOutAfterTheCodeIsSavedAndAFailedOneDoesntCount() {
		mail.jdbc = jdbc;
		try {
			// Sent outside the transaction: another connection can already see the code.
			auth.requestCode(null, "ama@example.com", "203.0.113.30");
			assertThat(mail.savedWhenSent).isOne();

			mail.down = true;
			for (int i = 0; i < SignInCode.MAX_PER_HOUR; i++) {
				assertRefused(() -> auth.requestCode(null, "ama@example.com", "203.0.113.30"), ErrorCode.CONFLICT,
						"We couldn’t send the email just now. Please try again in a minute.");
			}
			mail.down = false;
			// The failures took their codes back with them, so they didn't use up the hour's five.
			assertThat(auth.requestCode(null, "ama@example.com", "203.0.113.30")).isEmpty();
			assertThat(jdbc.sql("SELECT count(*) FROM sign_in_codes").query(Long.class).single()).isEqualTo(2);
		}
		finally {
			mail.down = false;
			mail.jdbc = null;
		}
	}

	@Test
	void aNumberGetsTenCodesADay() {
		var connection = "203.0.113.21";
		for (int hour = 0; hour < SignInCode.MAX_PER_DAY / SignInCode.MAX_PER_HOUR; hour++) {
			for (int i = 0; i < SignInCode.MAX_PER_HOUR; i++) {
				auth.requestCode("024 455 5124", null, connection);
			}
			clock.advance(Duration.ofMinutes(61));
		}
		assertRefused(() -> auth.requestCode("024 455 5124", null, connection), ErrorCode.CONFLICT,
				"Too many codes sent to this number today. Try again tomorrow.");
		clock.advance(Duration.ofDays(1));
		assertThat(auth.requestCode("024 455 5124", null, connection)).as("a day later, it can sign in again").isEmpty();
	}

	@Test
	void newCodesDontBuyMoreGuessesThanTheDaysLimit() {
		var connection = "203.0.113.22";
		var codes = SignInCode.MAX_WRONG_PER_DAY / SignInCode.MAX_WRONG_GUESSES;
		for (int c = 0; c < codes; c++) {
			// A moment apart, as real requests are: "the newest code" needs telling apart.
			clock.advance(Duration.ofSeconds(1));
			auth.requestCode("024 455 5125", null, connection);
			var wrong = sms.code().equals("000000") ? "111111" : "000000";
			for (int i = 0; i < SignInCode.MAX_WRONG_GUESSES; i++) {
				assertRefused(() -> auth.signIn("024 455 5125", null, wrong), ErrorCode.INVALID, null);
			}
		}
		// A fresh code, and the right one, still doesn't get in today: the guesses are spent.
		clock.advance(Duration.ofSeconds(1));
		auth.requestCode("024 455 5125", null, connection);
		assertRefused(() -> auth.signIn("024 455 5125", null, sms.code()), ErrorCode.INVALID,
				"Too many wrong tries today. Try again tomorrow, or sign in another way.");

		clock.advance(Duration.ofDays(1).plusMinutes(1));
		auth.requestCode("024 455 5125", null, connection);
		assertThat(auth.signIn("024 455 5125", null, sms.code()).user()).isNotNull();
	}

	@Test
	void aNumberGetsFiveCodesAnHour() {
		for (int i = 0; i < SignInCode.MAX_PER_HOUR; i++) {
			auth.requestCode("024 455 5123", null, CONNECTION);
		}
		assertRefused(() -> auth.requestCode("024 455 5123", null, CONNECTION), ErrorCode.CONFLICT, null);
	}

	@Test
	void peopleSignUpFromAnyCountryByEmailAndByTextFromGhana() {
		// Texts go to Ghanaian numbers only, for now: elsewhere it's email (or Google).
		assertRefused(() -> auth.requestCode("+44 7400 123456", null, CONNECTION), ErrorCode.INVALID,
				"Texts go to Ghanaian numbers only for now. Use your email address or Google instead.");

		auth.requestCode(null, "chidi@example.com", CONNECTION);
		assertThat(auth.signIn(null, "chidi@example.com", mail.code(), "ng").user().country()).as("by email, where the app thinks they are").isEqualTo("NG");
		auth.requestCode(null, "someone@example.com", CONNECTION);
		assertThat(auth.signIn(null, "someone@example.com", mail.code(), "ZZ").user().country()).as("not a country: Ghana").isEqualTo("GH");
	}

	@Test
	void invalidNumbersAreRefused() {
		assertRefused(() -> auth.requestCode("12345", null, CONNECTION), ErrorCode.INVALID, "Enter a valid mobile number, like 024 123 4567.");
	}

	@Test
	void sessionsLastUntilSignOut() {
		auth.requestCode("024 455 5123", null, CONNECTION);
		var signedIn = auth.signIn("024 455 5123", null, sms.code());

		assertThat(auth.userIdFor(signedIn.token())).contains(signedIn.user().id());
		assertThat(auth.userIdFor("not-a-real-token")).isEmpty();

		auth.signOut(signedIn.token());
		assertThat(auth.userIdFor(signedIn.token())).isEmpty();
	}

	@Test
	void anEmailAddressSignsInToo() {
		auth.requestCode(null, " Kwame@Example.com ", CONNECTION);
		assertThat(mail.last.to()).isEqualTo("kwame@example.com");
		assertThat(mail.last.subject()).startsWith("Your PlayChale sign-in code: ");
		assertThat(mail.last.text()).as("a plain-text copy for every mail app").contains(mail.code());
		assertThat(mail.last.html()).as("and the branded one").contains(">" + mail.code() + "<", "http://localhost:3000/icons/icon-192.png");

		var first = auth.signIn(null, "kwame@example.com", mail.code());
		assertThat(first.user().signInEmail()).isEqualTo("kwame@example.com");
		assertThat(first.user().email()).as("receipts go there to start with").isEqualTo("kwame@example.com");
		assertThat(first.user().phone()).isEmpty();

		auth.requestCode(null, "KWAME@example.COM", CONNECTION);
		assertThat(auth.signIn(null, "kwame@EXAMPLE.com", mail.code()).user().id()).as("the same account, however it's typed")
			.isEqualTo(first.user().id());

		auth.requestCode("024 455 5123", null, CONNECTION);
		assertThat(auth.signIn("024 455 5123", null, sms.code()).user().id()).as("a phone is a separate account")
			.isNotEqualTo(first.user().id());
	}

	@Test
	void oneWayToSignInAtATime() {
		assertRefused(() -> auth.requestCode(null, "not an email", CONNECTION), ErrorCode.INVALID, "Enter an email address like name@example.com.");
		assertRefused(() -> auth.requestCode("024 455 5123", "kwame@example.com", CONNECTION), ErrorCode.INVALID,
				"Enter your mobile number or your email address.");
		assertRefused(() -> auth.requestCode(null, null, CONNECTION), ErrorCode.INVALID, "Enter your mobile number or your email address.");
		assertThat(auth.options()).isEqualTo(new AuthService.Options(true, true, null));
	}

}
