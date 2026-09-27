package com.playchale.api.integration.push;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The cryptography Web Push needs, with the JDK alone: encrypting a message for one browser
 * (RFC 8291, "aes128gcm") and P-256 keys in the raw forms browsers use.
 */
final class WebPushCrypto {

	private static final SecureRandom random = new SecureRandom();

	/** One record holds the whole message: push services accept up to 4096 bytes. */
	static final int RECORD_SIZE = 4096;

	private static final ECParameterSpec P256 = p256();

	private WebPushCrypto() {
	}

	/**
	 * Encrypts {@code plaintext} for the browser whose public key and auth secret are given. The
	 * result is the request body: salt, record size, our ephemeral public key, then the ciphertext.
	 */
	static byte[] encrypt(byte[] plaintext, byte[] browserPublicKey, byte[] authSecret) throws GeneralSecurityException {
		var salt = new byte[16];
		random.nextBytes(salt);
		return encrypt(plaintext, browserPublicKey, authSecret, generateKeyPair(), salt);
	}

	/** As above, with the ephemeral key pair and salt given (tests). */
	static byte[] encrypt(byte[] plaintext, byte[] browserPublicKey, byte[] authSecret, KeyPair ephemeral, byte[] salt)
			throws GeneralSecurityException {
		var serverPublic = encodePublic((ECPublicKey) ephemeral.getPublic());
		var agreement = KeyAgreement.getInstance("ECDH");
		agreement.init(ephemeral.getPrivate());
		agreement.doPhase(decodePublic(browserPublicKey), true);
		var sharedSecret = agreement.generateSecret();

		var keyInfo = concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[] { 0 }, browserPublicKey, serverPublic);
		var ikm = hkdf(authSecret, sharedSecret, keyInfo, 32);
		var cek = hkdf(salt, ikm, concat("Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII), new byte[] { 0 }), 16);
		var nonce = hkdf(salt, ikm, concat("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII), new byte[] { 0 }), 12);

		// The last (and only) record ends with the 0x02 delimiter; no padding.
		var record = concat(plaintext, new byte[] { 2 });
		if (record.length + 16 + 86 > RECORD_SIZE) {
			throw new IllegalArgumentException("A push message can be at most about 4KB.");
		}
		var cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
		var ciphertext = cipher.doFinal(record);

		var header = ByteBuffer.allocate(16 + 4 + 1 + serverPublic.length);
		header.put(salt).putInt(RECORD_SIZE).put((byte) serverPublic.length).put(serverPublic);
		return concat(header.array(), ciphertext);
	}

	/** HKDF with SHA-256 (RFC 5869): extract, then the first {@code length} bytes of one expand block. */
	static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
		var prk = hmac(salt, ikm);
		return Arrays.copyOf(hmac(prk, concat(info, new byte[] { 1 })), length);
	}

	private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
		var mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(key, "HmacSHA256"));
		return mac.doFinal(data);
	}

	static KeyPair generateKeyPair() throws GeneralSecurityException {
		var generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new ECGenParameterSpec("secp256r1"));
		return generator.generateKeyPair();
	}

	/** A public key as browsers give it: 0x04, then X and Y, 32 bytes each. */
	static ECPublicKey decodePublic(byte[] raw) throws GeneralSecurityException {
		if (raw.length != 65 || raw[0] != 4) {
			throw new GeneralSecurityException("Not an uncompressed P-256 public key.");
		}
		var point = new ECPoint(new BigInteger(1, Arrays.copyOfRange(raw, 1, 33)), new BigInteger(1, Arrays.copyOfRange(raw, 33, 65)));
		return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, P256));
	}

	static byte[] encodePublic(ECPublicKey key) {
		return concat(new byte[] { 4 }, unsigned32(key.getW().getAffineX()), unsigned32(key.getW().getAffineY()));
	}

	/** A private key from its 32-byte scalar, as {@code web-push generate-vapid-keys} prints it. */
	static ECPrivateKey decodePrivate(byte[] raw) throws GeneralSecurityException {
		if (raw.length != 32) {
			throw new GeneralSecurityException("Not a 32-byte P-256 private key.");
		}
		return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, raw), P256));
	}

	static byte[] unsigned32(BigInteger value) {
		var bytes = value.toByteArray();
		if (bytes.length == 32) {
			return bytes;
		}
		var out = new byte[32];
		if (bytes.length > 32) {
			System.arraycopy(bytes, bytes.length - 32, out, 0, 32);
		}
		else {
			System.arraycopy(bytes, 0, out, 32 - bytes.length, bytes.length);
		}
		return out;
	}

	static byte[] base64url(String value) {
		return Base64.getUrlDecoder().decode(value.strip().replace('+', '-').replace('/', '_').replace("=", ""));
	}

	static String base64url(byte[] value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}

	static byte[] concat(byte[]... parts) {
		var length = 0;
		for (var part : parts) {
			length += part.length;
		}
		var out = new byte[length];
		var at = 0;
		for (var part : parts) {
			System.arraycopy(part, 0, out, at, part.length);
			at += part.length;
		}
		return out;
	}

	private static ECParameterSpec p256() {
		try {
			var parameters = AlgorithmParameters.getInstance("EC");
			parameters.init(new ECGenParameterSpec("secp256r1"));
			return parameters.getParameterSpec(ECParameterSpec.class);
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("This JVM has no P-256 curve.", e);
		}
	}

}
