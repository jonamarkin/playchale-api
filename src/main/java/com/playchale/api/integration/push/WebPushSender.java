package com.playchale.api.integration.push;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sends Web Push messages straight to the browsers' push services: encrypted, and signed as PlayChale's. */
class WebPushSender implements PushSender {

	private static final Logger log = LoggerFactory.getLogger("push");

	/** A notification still worth showing a day later (a result, a payment); after that, not. */
	private static final String TTL = String.valueOf(Duration.ofDays(1).toSeconds());

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final HttpClient http;

	private final Vapid vapid;

	private final Clock clock;

	WebPushSender(HttpClient http, Vapid vapid, Clock clock) {
		this.http = http;
		this.vapid = vapid;
		this.clock = clock;
	}

	@Override
	public String publicKey() {
		return vapid.publicKey();
	}

	@Override
	public Delivery send(PushTarget target, String payload) {
		if (!target.trusted()) {
			return Delivery.GONE;
		}
		try {
			var body = WebPushCrypto.encrypt(payload.getBytes(StandardCharsets.UTF_8), WebPushCrypto.base64url(target.p256dh()),
					WebPushCrypto.base64url(target.auth()));
			var request = HttpRequest.newBuilder(URI.create(target.endpoint()))
				.timeout(TIMEOUT)
				.header("Content-Encoding", "aes128gcm")
				.header("Content-Type", "application/octet-stream")
				.header("TTL", TTL)
				.header("Urgency", "normal")
				.header("Authorization", vapid.authorization(target.endpoint(), clock.instant()))
				.POST(HttpRequest.BodyPublishers.ofByteArray(body))
				.build();
			var status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
			if (status >= 200 && status < 300) {
				return Delivery.SENT;
			}
			if (status == 404 || status == 410) {
				return Delivery.GONE;
			}
			log.warn("Push to {} refused: {}", URI.create(target.endpoint()).getHost(), status);
			return Delivery.FAILED;
		}
		catch (GeneralSecurityException | IllegalArgumentException e) {
			// Keys the browser gave that can't be used: the subscription is no good.
			return Delivery.GONE;
		}
		catch (IOException e) {
			log.warn("Push to {} failed: {}", URI.create(target.endpoint()).getHost(), e.getMessage());
			return Delivery.FAILED;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Delivery.FAILED;
		}
	}

}
