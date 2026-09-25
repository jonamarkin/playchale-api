package com.playchale.api.auth.internal.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionTest {

	private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");

	@Test
	void aSessionIsKnownOnlyByTheHashOfItsToken() {
		var token = SessionToken.generate();
		var session = new Session(token, UUID.randomUUID(), NOW);

		assertThat(session.getId()).isEqualTo(token.hash()).isNotEqualTo(token.value()).hasSize(64);
		assertThat(session.getExpiresAt()).isEqualTo(NOW.plus(Session.LIFETIME));
		assertThat(token.toString()).doesNotContain(token.value());
	}

	@Test
	void lastSeenIsKeptToTheHourSoMostRequestsWriteNothing() {
		var session = new Session(SessionToken.generate(), UUID.randomUUID(), NOW);

		session.seen(NOW.plus(Duration.ofMinutes(59)));
		assertThat(session.getLastSeenAt()).isEqualTo(NOW);
		session.seen(NOW.plus(Duration.ofMinutes(61)));
		assertThat(session.getLastSeenAt()).isEqualTo(NOW.plus(Duration.ofMinutes(61)));
	}

}
