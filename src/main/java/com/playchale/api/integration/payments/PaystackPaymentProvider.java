package com.playchale.api.integration.payments;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.playchale.api.shared.error.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Payments through Paystack's hosted checkout (https://paystack.com/docs/api/transaction/). The
 * payer is sent to Paystack's page for mobile money or card, so each network's own steps and card
 * details stay with Paystack; they come back to the game when they're done.
 *
 * <p>Paystack's answer to "verify this transaction" is the only thing that says money moved. A
 * webhook is taken as a hint to go and ask. A transaction Paystack calls "abandoned" is just one
 * nobody has paid yet (the payer may still be on the checkout page), so it only counts as failed
 * after {@link #UNFINISHED_AFTER}.
 */
class PaystackPaymentProvider implements PaymentProvider {

	static final Duration UNFINISHED_AFTER = Duration.ofMinutes(30);

	private static final Logger log = LoggerFactory.getLogger(PaystackPaymentProvider.class);

	private final RestClient http;

	private final byte[] secret;

	private final ObjectMapper json;

	private final Clock clock;

	PaystackPaymentProvider(RestClient.Builder http, PaystackProperties properties, ObjectMapper json, Clock clock) {
		this.http = http.baseUrl(properties.baseUrl())
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.secretKey())
			.build();
		this.secret = properties.secretKey().getBytes(StandardCharsets.UTF_8);
		this.json = json;
		this.clock = clock;
	}

	/** The payer gives their number on Paystack's page. */
	@Override
	public boolean needsPayerPhone() {
		return false;
	}

	/** Paystack won't start a transaction without one; it emails its receipt there. */
	@Override
	public boolean needsEmail() {
		return true;
	}

	@Override
	public Started charge(Charge charge) {
		var body = new LinkedHashMap<String, Object>();
		body.put("email", charge.email());
		body.put("amount", charge.amount());
		body.put("currency", charge.currency());
		body.put("reference", charge.reference());
		body.put("channels", List.of(charge.method().startsWith("momo-") ? "mobile_money" : "card"));
		if (charge.returnUrl() != null) {
			body.put("callback_url", charge.returnUrl());
		}
		var answer = call(() -> http.post().uri("/transaction/initialize").contentType(MediaType.APPLICATION_JSON)
			.body(json.writeValueAsString(body)).retrieve().body(String.class), "start");
		var url = answer.path("data").path("authorization_url").asString("");
		if (url.isBlank()) {
			log.error("Paystack started {} without a checkout link: {}", charge.reference(), answer.path("message").asString(""));
			throw BusinessException.paymentFailed("We couldn’t start the payment with Paystack. Please try again.");
		}
		return new Started(url);
	}

	@Override
	public Status check(Charge charge, Instant startedAt) {
		var answer = call(() -> http.get().uri("/transaction/verify/{reference}", charge.reference()).retrieve().body(String.class), "check");
		var data = answer.path("data");
		var status = data.path("status").asString("");
		return switch (status) {
			case "success" -> {
				// Never take more (or less, or another currency) than was asked for as "paid".
				if (data.path("amount").asLong(-1) != charge.amount() || !charge.currency().equalsIgnoreCase(data.path("currency").asString(""))) {
					log.error("Paystack says {} succeeded for {} {}, but we asked for {} {}. Left pending for a person to look at.",
							charge.reference(), data.path("amount").asLong(-1), data.path("currency").asString(""), charge.amount(), charge.currency());
					yield Status.pending();
				}
				yield Status.succeeded();
			}
			case "failed" -> Status.failed("The payment was declined. No money was taken.");
			case "reversed" -> Status.failed("The payment was reversed, so the money went back to you.");
			case "abandoned" -> clock.instant().isAfter(startedAt.plus(UNFINISHED_AFTER))
					? Status.failed("The payment wasn’t finished. No money was taken.") : Status.pending();
			default -> Status.pending(); // ongoing, pending, processing, queued
		};
	}

	/** Paystack signs each webhook with HMAC-SHA512 of its exact body, keyed with the secret key. */
	@Override
	public Optional<String> webhookReference(String body, String signature) {
		if (body == null || signature == null) {
			return Optional.empty();
		}
		byte[] expected;
		try {
			var mac = Mac.getInstance("HmacSHA512");
			mac.init(new SecretKeySpec(secret, "HmacSHA512"));
			expected = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA512 is always available", e);
		}
		if (!MessageDigest.isEqual(expected, signature.strip().toLowerCase().getBytes(StandardCharsets.UTF_8))) {
			return Optional.empty();
		}
		var event = json.readTree(body);
		if (!event.path("event").asString("").startsWith("charge.")) {
			return Optional.empty();
		}
		var reference = event.path("data").path("reference").asString("");
		return reference.isBlank() ? Optional.empty() : Optional.of(reference);
	}

	private interface Request {

		String send();

	}

	/**
	 * One call to Paystack. Their answers always say {"status": true|false, "message", "data"}; a
	 * refusal or an outage is logged in full and reported to the payer in plain words.
	 */
	private JsonNode call(Request request, String what) {
		String body;
		try {
			body = request.send();
		}
		catch (RestClientResponseException e) {
			log.error("Paystack refused to {} a payment: {} {}", what, e.getStatusCode(), e.getResponseBodyAsString());
			throw BusinessException.paymentFailed("Paystack couldn’t %s the payment just now. Please try again.".formatted(what));
		}
		catch (RuntimeException e) {
			log.error("Couldn't reach Paystack to {} a payment", what, e);
			throw BusinessException.paymentFailed("We couldn’t reach Paystack. Please try again.");
		}
		return json.readTree(body == null ? "{}" : body);
	}

}
