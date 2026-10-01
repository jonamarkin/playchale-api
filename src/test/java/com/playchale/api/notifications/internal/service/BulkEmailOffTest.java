package com.playchale.api.notifications.internal.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.playchale.api.TestcontainersConfiguration;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The default. A copy of the API that was not deliberately told it may send to a list does not, even
 * with a working mail provider and real addresses — the damage is one-way, and a staging database
 * holds real people.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, BulkEmailOffTest.Fakes.class })
class BulkEmailOffTest {

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

		@Bean
		@Primary
		PushQueue inlineQueue() {
			return Runnable::run;
		}

	}

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

	@Test
	void nothingLeavesAndTheRecordSaysWhy() {
		var id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO users (id, phone, country, name, handle, tint, sign_in_email, onboarded, created_at, updated_at)
				VALUES (:id, '+233240001234', 'GH', 'Kwame', 'kwame', '#e8e8e4', 'kwame@playchale.test', true, :now, :now)
				""").param("id", id).param("now", Instant.now().atOffset(ZoneOffset.UTC)).update();

		var mailout = mailouts.send(new Mailouts.Letter("digest", "digest", "Your week", "Results"), null,
				List.of(new Mailouts.Copy(id, "kwame@playchale.test",
						Map.of("greeting", "Your week", "week", "1 – 8 Oct", "yours", "Two games.", "competitions", "Nothing.",
								"actionUrl", "https://playchale.com/home"),
						"https://playchale.com/unsubscribe?token=x")));

		assertThat(sender.sent).isEmpty();
		assertThat(jdbc.sql("SELECT reason FROM mailout_recipients WHERE mailout_id = :id").param("id", mailout).query(String.class).single())
			.isEqualTo("not sent: bulk email is off here");
	}

}
