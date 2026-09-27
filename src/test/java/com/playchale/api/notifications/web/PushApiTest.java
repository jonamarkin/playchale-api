package com.playchale.api.notifications.web;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import com.playchale.api.TestSignIn;
import com.playchale.api.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phone notification settings over HTTP, with a subscription exactly as a browser serialises it. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PushApiTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void emptyTables() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, push_subscriptions, push_preferences CASCADE").update();
	}

	@Test
	void aBrowsersSubscriptionIsAcceptedAsItIs() throws Exception {
		var kojo = TestSignIn.as(mvc, "024 455 5124");
		mvc.perform(get("/me/push")).andExpect(status().isUnauthorized());
		mvc.perform(get("/me/push").cookie(kojo))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.publicKey").isNotEmpty())
			.andExpect(jsonPath("$.devices").value(0));

		// What PushSubscription.toJSON() gives, extra fields and all.
		var subscription = """
				{"endpoint":"https://fcm.googleapis.com/fcm/send/abc","expirationTime":null,"keys":{"p256dh":"%s","auth":"%s"}}
				""".formatted(browserKey(), Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]));
		mvc.perform(post("/me/push/subscriptions").cookie(kojo).contentType(MediaType.APPLICATION_JSON).content(subscription))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.devices").value(1));
		mvc.perform(patch("/me/push").cookie(kojo).contentType(MediaType.APPLICATION_JSON).content("{\"muted\":[\"payments\"]}"))
			.andExpect(jsonPath("$.muted[0]").value("payments"));
		mvc.perform(post("/me/push/unsubscriptions").cookie(kojo).contentType(MediaType.APPLICATION_JSON)
			.content("{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/abc\"}"))
			.andExpect(jsonPath("$.devices").value(0));
	}

	private static String browserKey() throws Exception {
		var generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new ECGenParameterSpec("secp256r1"));
		var point = ((ECPublicKey) generator.generateKeyPair().getPublic()).getW();
		var raw = new byte[65];
		raw[0] = 4;
		copy(point.getAffineX(), raw, 1);
		copy(point.getAffineY(), raw, 33);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
	}

	private static void copy(BigInteger value, byte[] into, int at) {
		var bytes = value.toByteArray();
		var length = Math.min(32, bytes.length);
		System.arraycopy(bytes, bytes.length - length, into, at + 32 - length, length);
	}

}
