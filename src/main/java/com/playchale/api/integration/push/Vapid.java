package com.playchale.api.integration.push;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.time.Duration;
import java.time.Instant;

/**
 * Signs the token that tells a push service a message comes from PlayChale (VAPID, RFC 8292): a JWT
 * for the service's origin, signed with our private key, sent with our public key.
 */
final class Vapid {

	/** Push services accept tokens valid for up to a day. */
	private static final Duration VALID_FOR = Duration.ofHours(12);

	private final ECPrivateKey privateKey;

	private final String publicKey;

	private final String subject;

	Vapid(PushProperties properties) throws GeneralSecurityException {
		this.privateKey = WebPushCrypto.decodePrivate(WebPushCrypto.base64url(properties.vapidPrivateKey()));
		var publicRaw = WebPushCrypto.base64url(properties.vapidPublicKey());
		this.publicKey = WebPushCrypto.base64url(publicRaw);
		this.subject = properties.subject();
		// The two keys have to be a pair, or every browser would reject every message: find out now.
		var check = "playchale".getBytes(StandardCharsets.US_ASCII);
		var verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
		verifier.initVerify(WebPushCrypto.decodePublic(publicRaw));
		verifier.update(check);
		if (!verifier.verify(sign(check))) {
			throw new GeneralSecurityException("The VAPID public and private keys aren’t a pair. Generate them together.");
		}
	}

	String publicKey() {
		return publicKey;
	}

	/** The Authorization header for a message to {@code endpoint}. */
	String authorization(String endpoint, Instant now) throws GeneralSecurityException {
		var uri = URI.create(endpoint);
		var audience = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
		var header = encode("{\"typ\":\"JWT\",\"alg\":\"ES256\"}");
		var claims = encode("{\"aud\":\"%s\",\"exp\":%d,\"sub\":\"%s\"}".formatted(json(audience), now.plus(VALID_FOR).getEpochSecond(), json(subject)));
		var unsigned = header + "." + claims;
		return "vapid t=%s.%s, k=%s".formatted(unsigned, WebPushCrypto.base64url(sign(unsigned.getBytes(StandardCharsets.US_ASCII))), publicKey);
	}

	private byte[] sign(byte[] data) throws GeneralSecurityException {
		// ES256 in a JWT is the raw 64-byte r||s, which is what the P1363 format gives.
		var signer = Signature.getInstance("SHA256withECDSAinP1363Format");
		signer.initSign(privateKey);
		signer.update(data);
		return signer.sign();
	}

	private static String encode(String json) {
		return WebPushCrypto.base64url(json.getBytes(StandardCharsets.UTF_8));
	}

	private static String json(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

}
