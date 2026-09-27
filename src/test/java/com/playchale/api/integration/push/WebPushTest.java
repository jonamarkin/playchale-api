package com.playchale.api.integration.push;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Web Push without a browser: the encryption against the standard's own example, and the VAPID token. */
class WebPushTest {

	/** RFC 8291, Appendix A: every input fixed, so the message is known byte for byte. */
	@Test
	void encryptsExactlyAsTheStandardsExample() throws Exception {
		var serverKeys = new KeyPair(WebPushCrypto.decodePublic(b64("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8")),
				WebPushCrypto.decodePrivate(b64("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw")));
		var body = WebPushCrypto.encrypt("When I grow up, I want to be a watermelon".getBytes(StandardCharsets.UTF_8),
				b64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"), b64("BTBZMqHH6r4Tts7J_aSIgg"),
				serverKeys, b64("DGv6ra1nlYgDCS1FRnbzlw"));
		assertThat(WebPushCrypto.base64url(body)).isEqualTo("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN");
	}

	/** A browser decrypting what we send: its own keys, a fresh salt and server key each time. */
	@Test
	void theBrowserCanReadWhatWeSend() throws Exception {
		var browser = WebPushCrypto.generateKeyPair();
		var browserPublic = WebPushCrypto.encodePublic((java.security.interfaces.ECPublicKey) browser.getPublic());
		var auth = new byte[16];
		new java.security.SecureRandom().nextBytes(auth);
		var message = "{\"title\":\"Kojo invited you to Saturday 5s\"}";

		var body = WebPushCrypto.encrypt(message.getBytes(StandardCharsets.UTF_8), browserPublic, auth);
		var header = ByteBuffer.wrap(body);
		var salt = new byte[16];
		header.get(salt);
		assertThat(header.getInt()).isEqualTo(4096);
		var serverPublic = new byte[header.get()];
		header.get(serverPublic);
		var ciphertext = Arrays.copyOfRange(body, header.position(), body.length);

		var agreement = KeyAgreement.getInstance("ECDH");
		agreement.init(browser.getPrivate());
		agreement.doPhase(WebPushCrypto.decodePublic(serverPublic), true);
		var info = WebPushCrypto.concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[] { 0 }, browserPublic, serverPublic);
		var ikm = WebPushCrypto.hkdf(auth, agreement.generateSecret(), info, 32);
		var cek = WebPushCrypto.hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
		var nonce = WebPushCrypto.hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
		var cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
		var record = cipher.doFinal(ciphertext);
		assertThat(record[record.length - 1]).as("the last record's delimiter").isEqualTo((byte) 2);
		assertThat(new String(record, 0, record.length - 1, StandardCharsets.UTF_8)).isEqualTo(message);
	}

	@Test
	void theVapidTokenIsSignedForThePushServiceAndChecksTheKeysArePaired() throws Exception {
		var keys = WebPushCrypto.generateKeyPair();
		var publicKey = WebPushCrypto.base64url(WebPushCrypto.encodePublic((java.security.interfaces.ECPublicKey) keys.getPublic()));
		var privateKey = WebPushCrypto.base64url(WebPushCrypto.unsigned32(((java.security.interfaces.ECPrivateKey) keys.getPrivate()).getS()));
		var vapid = new Vapid(new PushProperties(publicKey, privateKey, "mailto:alert@playchale.com"));

		var header = vapid.authorization("https://fcm.googleapis.com/fcm/send/abc123", Instant.parse("2026-09-27T10:00:00Z"));
		assertThat(header).startsWith("vapid t=").endsWith(", k=" + publicKey);
		var jwt = header.substring("vapid t=".length(), header.indexOf(", k="));
		var parts = jwt.split("\\.");
		var claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
		assertThat(claims).isEqualTo("{\"aud\":\"https://fcm.googleapis.com\",\"exp\":1790546400,\"sub\":\"mailto:alert@playchale.com\"}");
		var verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
		verifier.initVerify(keys.getPublic());
		verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
		assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();

		var other = WebPushCrypto.generateKeyPair();
		var otherPublic = WebPushCrypto.base64url(WebPushCrypto.encodePublic((java.security.interfaces.ECPublicKey) other.getPublic()));
		assertThatThrownBy(() -> new Vapid(new PushProperties(otherPublic, privateKey, "mailto:alert@playchale.com")))
			.hasMessage("The VAPID public and private keys aren’t a pair. Generate them together.");
	}

	@Test
	void messagesOnlyGoToTheBrowsersPushServices() {
		assertThat(target("https://fcm.googleapis.com/fcm/send/abc").trusted()).isTrue();
		assertThat(target("https://web.push.apple.com/QGuQyavXutnMYd").trusted()).isTrue();
		assertThat(target("https://updates.push.services.mozilla.com/wpush/v2/abc").trusted()).isTrue();
		assertThat(target("https://wns2-db5p.notify.windows.com/w/?token=abc").trusted()).isTrue();
		assertThat(target("http://fcm.googleapis.com/fcm/send/abc").trusted()).as("not https").isFalse();
		assertThat(target("https://localhost:8080/admin").trusted()).isFalse();
		assertThat(target("https://fcm.googleapis.com.evil.example/x").trusted()).isFalse();
		assertThat(target("https://evilfcm.googleapis.com/x").trusted()).as("a lookalike, not a subdomain").isFalse();
		assertThat(target("not a url").trusted()).isFalse();
	}

	private static PushTarget target(String endpoint) {
		return new PushTarget(endpoint, "", "");
	}

	private static byte[] b64(String value) {
		return WebPushCrypto.base64url(value);
	}

}
