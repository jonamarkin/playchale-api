package com.playchale.api.notifications.internal.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.integration.email.BulkEmailProperties;
import com.playchale.api.integration.email.Email;
import com.playchale.api.integration.email.EmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.convention.TestBean;

import static org.assertj.core.api.Assertions.assertThat;

/** Sending one message to many people: who it reaches, who it doesn't, and what the record says. */
@SpringBootTest(properties = "playchale.email.bulk=true")
@Import({ TestcontainersConfiguration.class, MailoutsTest.Fakes.class })
class MailoutsTest {

	/** Every email that would have left, in order. */
	static class RecordingEmailSender implements EmailSender {

		final List<Email> sent = new ArrayList<>();

		@Override
		public void send(Email email) {
			sent.add(email);
		}

	}

	@TestConfiguration
	static class Fakes {

		@Bean
		@Primary
		RecordingEmailSender recordingEmailSender() {
			return new RecordingEmailSender();
		}

		/** Sending on the test's thread, so the test can see it. */
		@Bean
		@Primary
		PushQueue inlineQueue() {
			return Runnable::run;
		}

	}

	private static final Mailouts.Letter LETTER = new Mailouts.Letter("announcement", "announcement", "Something new", "Have a look");

	@Autowired
	Mailouts mailouts;

	@Autowired
	RecordingEmailSender sender;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void emptyTables() {
		jdbc.sql("TRUNCATE users, mailouts, mailout_recipients CASCADE").update();
		sender.sent.clear();
	}

	private UUID player(String name, String email) {
		var id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO users (id, phone, country, name, handle, tint, sign_in_email, onboarded, created_at, updated_at)
				VALUES (:id, :phone, 'GH', :name, :handle, '#e8e8e4', :email, true, :now, :now)
				""")
			.param("id", id).param("phone", "+23324" + id.toString().replaceAll("\\D", "").substring(0, 7)).param("name", name)
			.param("handle", name.toLowerCase() + id.toString().substring(0, 4)).param("email", email)
			.param("now", Instant.now().atOffset(java.time.ZoneOffset.UTC))
			.update();
		return id;
	}

	private Mailouts.Copy copyFor(UUID id, String address) {
		return new Mailouts.Copy(id, address, Map.of("label", "News", "heading", "Something new", "body", "A line.",
				"action", "Open PlayChale", "actionUrl", "https://playchale.com"), "https://playchale.com/unsubscribe?token=x");
	}

	@Test
	void everyoneWithAUsableAddressGetsItOnce() {
		var kwame = player("Kwame", "kwame@playchale.test");
		var abena = player("Abena", "abena@playchale.test");

		var id = mailouts.send(LETTER, null, List.of(copyFor(kwame, "kwame@playchale.test"), copyFor(abena, "abena@playchale.test")));

		assertThat(sender.sent).hasSize(2);
		assertThat(sender.sent).allSatisfy(email -> {
			assertThat(email.subject()).isEqualTo("Something new");
			// Every message that needed agreement carries the way out of it, in both forms.
			assertThat(email.html()).contains("Unsubscribe").contains("unsubscribe?token=x");
			assertThat(email.text()).contains("Stop these emails");
		});
		assertThat(statuses(id)).containsExactlyInAnyOrder("sent", "sent");
	}

	@Test
	void runningTheSameMailoutAgainSendsNobodyASecondCopy() {
		var kwame = player("Kwame", "kwame@playchale.test");
		var copies = List.of(copyFor(kwame, "kwame@playchale.test"));
		var id = mailouts.send(LETTER, null, copies);
		assertThat(sender.sent).hasSize(1);

		// A resumed or repeated run finds nothing left to claim: the pair (mailout, person) is the key.
		mailouts.run(id, LETTER, copies);

		assertThat(sender.sent).hasSize(1);
	}

	@Test
	void anAddressWeCannotUseIsWrittenDownRatherThanDropped() {
		// Reserved addresses never leave, whatever the setting: the demo data is full of them.
		var seeded = player("Seeded", "kwame@example.com");
		var noAddress = player("Nobody", null);

		var id = mailouts.send(LETTER, null, List.of(copyFor(seeded, "kwame@example.com"), copyFor(noAddress, null)));

		assertThat(sender.sent).isEmpty();
		assertThat(statuses(id)).containsExactlyInAnyOrder("skipped", "skipped");
		assertThat(jdbc.sql("SELECT DISTINCT reason FROM mailout_recipients WHERE mailout_id = :id").param("id", id).query(String.class).single())
			.isEqualTo("no usable address");
	}

	@Test
	void reservedAddressesAreNeverDeliverable() {
		assertThat(BulkEmailProperties.deliverable("kwame@example.com")).isFalse();
		assertThat(BulkEmailProperties.deliverable("kwame@EXAMPLE.ORG")).isFalse();
		assertThat(BulkEmailProperties.deliverable("")).isFalse();
		assertThat(BulkEmailProperties.deliverable(null)).isFalse();
		assertThat(BulkEmailProperties.deliverable("kwame@playchale.com")).isTrue();
	}

	private List<String> statuses(UUID mailoutId) {
		return jdbc.sql("SELECT status FROM mailout_recipients WHERE mailout_id = :id").param("id", mailoutId).query(String.class).list();
	}

}
