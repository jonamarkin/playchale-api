package com.playchale.api.integration.sms;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import com.playchale.api.shared.error.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
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
		// Rancard can answer 200 and still refuse, with "success": false.
		if (!json.readTree(answer == null ? "{}" : answer).path("success").asBoolean(false)) {
			log.error("Rancard refused a text to {}: {}", masked(msisdn), reason(answer));
			throw couldNotSend();
		}
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
