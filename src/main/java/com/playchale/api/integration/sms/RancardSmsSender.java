package com.playchale.api.integration.sms;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.playchale.api.shared.error.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Texts through Rancard's Bulk Messaging API
 * (https://bulkmessagingapi.rancard.com/api/v1/sms/public/sendMessage), one recipient at a time.
 *
 * <p>A text holds a sign-in code, so its words are never logged, and neither is a whole number: a
 * failure logs Rancard's answer and the number with its middle hidden, and the person is told to
 * try again.
 */
class RancardSmsSender implements SmsSender {

	private static final Logger log = LoggerFactory.getLogger(RancardSmsSender.class);

	private final RestClient http;

	private final String apiKey;

	private final String senderId;

	private final ObjectMapper json;

	RancardSmsSender(RestClient.Builder http, RancardProperties properties, ObjectMapper json) {
		var sender = properties.senderId() == null ? "" : properties.senderId().strip();
		if (sender.isEmpty() || sender.length() > 11) {
			throw new IllegalStateException(
					"PLAYCHALE_RANCARD_SENDER_ID must be the sender ID approved on unify.rancard.com (up to 11 characters), e.g. PlayChale");
		}
		this.http = http.baseUrl(properties.baseUrl()).build();
		this.apiKey = properties.apiKey();
		this.senderId = sender;
		this.json = json;
	}

	@Override
	public void send(String phone, String message) {
		// Rancard wants the number as digits with the country code and no plus: 233241234567.
		var msisdn = phone.startsWith("+") ? phone.substring(1) : phone;
		var body = new LinkedHashMap<String, Object>();
		body.put("apiKey", apiKey);
		body.put("senderId", senderId);
		body.put("message", message);
		body.put("contacts", List.of(msisdn));
		body.put("scheduled", false);
		body.put("hasPlaceholders", false);
		String answer;
		try {
			answer = http.post().uri("/api/v1/sms/public/sendMessage").contentType(MediaType.APPLICATION_JSON)
				// A retry by anything between us and Rancard mustn't text the same code twice.
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.body(json.writeValueAsString(body)).retrieve().body(String.class);
		}
		catch (RestClientResponseException e) {
			log.error("Rancard refused a text to {}: {} {}", masked(msisdn), e.getStatusCode(), reason(e.getResponseBodyAsString()));
			throw couldNotSend();
		}
		catch (RuntimeException e) {
			log.error("Couldn't reach Rancard to text {}: {}", masked(msisdn), e.toString());
			throw couldNotSend();
		}
		JsonNode said;
		try {
			said = json.readTree(answer == null ? "{}" : answer);
		}
		catch (RuntimeException notJson) {
			// It answered 200: the text is on its way, whatever the words.
			return;
		}
		if (refused(said)) {
			log.error("Rancard refused a text to {}: code {}, success {}: {}", masked(msisdn), said.path("code"), said.path("success"),
					reason(answer));
			throw couldNotSend();
		}
	}

	/**
	 * Whether Rancard's answer says no. It answers 200 either way: a refusal says {@code "success":
	 * false} or carries an error code (400 and up). Anything else is a text on its way, however it's
	 * worded ("SMS request is being processed"): calling a sent code a failure would have people ask
	 * for code after code.
	 */
	static boolean refused(JsonNode answer) {
		var success = answer.path("success");
		var saysNo = success.isBoolean() ? !success.booleanValue() : success.isString() && "false".equalsIgnoreCase(success.asString().strip());
		var code = answer.path("code");
		int number;
		try {
			number = code.isNumber() ? code.asInt() : code.isString() ? Integer.parseInt(code.asString().strip()) : 0;
		}
		catch (NumberFormatException notANumber) {
			number = 0;
		}
		// Its own words for a text queued to go out outweigh the rest of the answer.
		if (answer.path("message").asString("").toLowerCase(Locale.ROOT).contains("being processed")) {
			return false;
		}
		return saysNo || number >= 400;
	}

	/** Rancard's own words about why, from its answer: never the text, which only a success echoes back. */
	private String reason(String answer) {
		try {
			return json.readTree(answer == null ? "{}" : answer).path("message").asString("no reason given");
		}
		catch (RuntimeException notJson) {
			return "an answer that isn't JSON";
		}
	}

	/** 233241234567 as 23324•••4567: enough to tell numbers apart in a log, not enough to text one. */
	static String masked(String msisdn) {
		return msisdn.length() <= 7 ? "•••" : msisdn.substring(0, 5) + "•••" + msisdn.substring(msisdn.length() - 4);
	}

	private static BusinessException couldNotSend() {
		return BusinessException.conflict("We couldn’t send the text just now. Please try again in a minute.");
	}

}
