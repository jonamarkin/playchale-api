package com.playchale.api.notifications.internal.service;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.games.internal.domain.GameDetails;
import com.playchale.api.games.internal.service.GameService;
import com.playchale.api.integration.push.PushSender;
import com.playchale.api.integration.push.PushTarget;
import com.playchale.api.users.api.UserDirectory;
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

/** Phone notifications against a real Postgres, with a push service that records what it's sent. */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, PushServiceTest.Fakes.class })
class PushServiceTest {

	/** What would have gone to the browsers' push services. */
	static class RecordingPushSender implements PushSender {

		final List<String> sent = new ArrayList<>();

		final Set<String> gone = new HashSet<>();

		@Override
		public String publicKey() {
			return "BTestPublicKey";
		}

		@Override
		public Delivery send(PushTarget target, String payload) {
			if (gone.contains(target.endpoint())) {
				return Delivery.GONE;
			}
			sent.add(target.endpoint() + " " + payload);
			return Delivery.SENT;
		}

	}

	@TestConfiguration
	static class Fakes {

		@Bean
		@Primary
		RecordingPushSender recordingPushSender() {
			return new RecordingPushSender();
		}

		/** Deliveries straight away, on the test's thread, so the test can see them. */
		@Bean
		@Primary
		PushQueue inlinePushQueue() {
			return Runnable::run;
		}

	}

	private static final String PHONE = "https://fcm.googleapis.com/fcm/send/kojos-phone";

	@Autowired
	PushService push;

	@Autowired
	RecordingPushSender sender;

	@Autowired
	GameService games;

	@Autowired
	UserDirectory users;

	@Autowired
	JdbcClient jdbc;

	UUID kwame;

	UUID kojo;

	@BeforeEach
	void setUp() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, games, game_participants, game_invites, notifications, push_subscriptions, push_preferences CASCADE")
			.update();
		sender.sent.clear();
		sender.gone.clear();
		kwame = user("+233244555123", "Kwame Mensah");
		kojo = user("+233244555124", "Kojo Owusu");
	}

	private UUID user(String phone, String name) {
		var id = users.registerOrFind(phone, "GH").id();
		jdbc.sql("UPDATE users SET name = :name WHERE id = :id").param("name", name).param("id", id).update();
		return id;
	}

	private UUID game() {
		return games.create(new GameDetails("football", "5-a-side", "Saturday 5s", Instant.now().plus(Duration.ofDays(2)), 60, "unlisted", null,
				null, "Legon Park", "Legon", null, 10, 0, null, "public", null), kwame).id();
	}

	/** A browser's keys: a real P-256 public key and a 16-byte secret, base64url. */
	private static String[] keys() throws Exception {
		var generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new ECGenParameterSpec("secp256r1"));
		var point = ((ECPublicKey) generator.generateKeyPair().getPublic()).getW();
		var raw = new byte[65];
		raw[0] = 4;
		System.arraycopy(fixed(point.getAffineX()), 0, raw, 1, 32);
		System.arraycopy(fixed(point.getAffineY()), 0, raw, 33, 32);
		var encoder = Base64.getUrlEncoder().withoutPadding();
		return new String[] { encoder.encodeToString(raw), encoder.encodeToString(new byte[16]) };
	}

	private static byte[] fixed(BigInteger value) {
		var bytes = value.toByteArray();
		var out = new byte[32];
		System.arraycopy(bytes, Math.max(0, bytes.length - 32), out, Math.max(0, 32 - bytes.length), Math.min(32, bytes.length));
		return out;
	}

	@Test
	void aPhoneIsAddedOnlyWithAPushServiceAddressAndItsKeys() throws Exception {
		var keys = keys();
		assertThat(push.settings(kojo)).satisfies(s -> {
			assertThat(s.publicKey()).isEqualTo("BTestPublicKey");
			assertThat(s.devices()).isZero();
		});
		assertThatThrownBy(() -> push.subscribe(kojo, "https://intranet.example/hook", keys[0], keys[1]))
			.hasMessage("That isn’t a browser’s notification address.");
		assertThatThrownBy(() -> push.subscribe(kojo, PHONE, "short", keys[1])).hasMessage("That browser’s notification keys don’t look right.");

		assertThat(push.subscribe(kojo, PHONE, keys[0], keys[1]).devices()).isEqualTo(1);
		assertThat(push.subscribe(kojo, PHONE, keys[0], keys[1]).devices()).as("the same phone again").isEqualTo(1);
		assertThat(push.subscribe(kwame, PHONE, keys[0], keys[1]).devices()).as("someone else signed in on it").isEqualTo(1);
		assertThat(push.settings(kojo).devices()).isZero();
		assertThat(push.unsubscribe(kwame, PHONE).devices()).isZero();
	}

	@Test
	void notificationsReachThePhoneUnlessThatKindIsKeptOff() throws Exception {
		var keys = keys();
		push.subscribe(kojo, PHONE, keys[0], keys[1]);
		var game = game();

		games.invite(game, List.of(kojo), kwame);
		assertThat(sender.sent).singleElement().satisfies(sent -> {
			assertThat(sent).startsWith(PHONE + " {\"title\":\"Kwame invited you to Saturday 5s\"");
			assertThat(sent).contains("\"link\":\"/games/" + game + "\"");
		});

		assertThatThrownBy(() -> push.mute(kojo, List.of("gossip"))).hasMessage("Pick from the kinds of notification.");
		assertThat(push.mute(kojo, List.of("games")).muted()).containsExactly("games");
		games.invite(game, List.of(kojo), kwame);
		assertThat(sender.sent).as("games are kept off the phone now").hasSize(1);

		push.mute(kojo, List.of());
		sender.gone.add(PHONE);
		games.invite(game, List.of(kojo), kwame);
		assertThat(push.settings(kojo).devices()).as("a phone the push service no longer knows is forgotten").isZero();
	}

}
