package com.playchale.api.integration.email;

import java.util.LinkedHashMap;
import java.util.List;

import com.playchale.api.shared.error.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

/**
 * Emails through Resend's API (https://resend.com/docs/api-reference/emails/send-email).
 *
 * <p>A message can hold a sign-in code, so nothing about it (address aside) is ever logged: a
 * failure logs only Resend's answer, and the person is told to try again.
 */
class ResendEmailSender implements EmailSender {

	private static final Logger log = LoggerFactory.getLogger(ResendEmailSender.class);

	private final RestClient http;

	private final String from;

	private final ObjectMapper json;

	ResendEmailSender(RestClient.Builder http, ResendProperties properties, ObjectMapper json) {
		if (properties.from() == null || properties.from().isBlank()) {
			throw new IllegalStateException("PLAYCHALE_RESEND_FROM must name the sender, e.g. PlayChale <alert@playchale.com>");
		}
		this.http = http.baseUrl(properties.baseUrl())
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
			.build();
		this.from = properties.from();
		this.json = json;
	}

	@Override
	public void send(Email email) {
		var to = email.to();
		var body = new LinkedHashMap<String, Object>();
		body.put("from", from);
		body.put("to", List.of(to));
		body.put("subject", email.subject());
		body.put("text", email.text());
		if (email.html() != null) {
			body.put("html", email.html());
		}
		try {
			http.post().uri("/emails").contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body)).retrieve().toBodilessEntity();
		}
		catch (RestClientResponseException e) {
			log.error("Resend refused an email to {}: {} {}", to, e.getStatusCode(), e.getResponseBodyAsString());
			throw couldNotSend();
		}
		catch (RuntimeException e) {
			log.error("Couldn't reach Resend to email {}", to, e);
			throw couldNotSend();
		}
	}

	private static BusinessException couldNotSend() {
		return BusinessException.conflict("We couldn’t send the email just now. Please try again in a minute.");
	}

}
