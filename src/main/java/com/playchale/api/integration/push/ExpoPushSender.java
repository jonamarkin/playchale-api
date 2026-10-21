package com.playchale.api.integration.push;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The app's notifications, through Expo's push service, which hands each one to Google's (Android)
 * or Apple's (iPhone). One message per request: they're few, and each answer says whether its phone
 * is still there. An access token, when set, is sent with each; without one Expo still accepts them.
 */
class ExpoPushSender implements AppPushSender {

	private static final Logger log = LoggerFactory.getLogger("push");

	static final URI SEND = URI.create("https://exp.host/--/api/v2/push/send");

	private final HttpClient http;

	private final ObjectMapper json;

	private final String accessToken;

	ExpoPushSender(HttpClient http, ObjectMapper json, String accessToken) {
		this.http = http;
		this.json = json;
		this.accessToken = accessToken == null ? "" : accessToken.strip();
	}

	@Override
	public PushSender.Delivery send(String token, String title, String body, String link) {
		try {
			var message = new LinkedHashMap<String, Object>();
			message.put("to", token);
			message.put("title", title);
			message.put("body", body);
			message.put("data", Map.of("link", link));
			message.put("sound", "default");
			var request = HttpRequest.newBuilder(SEND)
				.timeout(Duration.ofSeconds(10))
				.header("Accept", "application/json")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(message)));
			if (!accessToken.isEmpty()) {
				request.header("Authorization", "Bearer " + accessToken);
			}
			var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 != 2) {
				log.warn("Expo push answered {}", response.statusCode());
				return PushSender.Delivery.FAILED;
			}
			return delivery(json.readTree(response.body()));
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return PushSender.Delivery.FAILED;
		}
		catch (Exception e) {
			log.warn("Couldn’t reach Expo push: {}", e.toString());
			return PushSender.Delivery.FAILED;
		}
	}

	/** Expo's answer: {"data": {"status": "ok"}}, or an error, "DeviceNotRegistered" meaning the app is gone. */
	static PushSender.Delivery delivery(JsonNode answer) {
		var data = answer.path("data");
		var ticket = data.isArray() ? data.path(0) : data;
		if ("ok".equals(ticket.path("status").asString(""))) {
			return PushSender.Delivery.SENT;
		}
		if ("DeviceNotRegistered".equals(ticket.path("details").path("error").asString(""))) {
			return PushSender.Delivery.GONE;
		}
		log.warn("Expo push refused a message: {}", ticket.path("message").asString(""));
		return PushSender.Delivery.FAILED;
	}

}
