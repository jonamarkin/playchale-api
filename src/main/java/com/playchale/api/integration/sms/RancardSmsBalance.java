package com.playchale.api.integration.sms;

import java.util.LinkedHashMap;
import java.util.OptionalLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** The Rancard SMS bundle's balance (https://unify-base.rancard.com/sms/balance). */
class RancardSmsBalance implements SmsBalance {

	private static final Logger log = LoggerFactory.getLogger(RancardSmsBalance.class);

	private final RestClient http;

	private final String apiKey;

	private final long lowAt;

	private final ObjectMapper json;

	RancardSmsBalance(RestClient.Builder http, RancardProperties properties, ObjectMapper json) {
		this.http = http.baseUrl(properties.balanceUrl()).build();
		this.apiKey = properties.apiKey();
		this.lowAt = properties.lowBalance();
		this.json = json;
	}

	@Override
	public OptionalLong credits() {
		var body = new LinkedHashMap<String, Object>();
		body.put("apiKey", apiKey);
		try {
			var answer = json.readTree(http.post().uri("/sms/balance").contentType(MediaType.APPLICATION_JSON)
				.body(json.writeValueAsString(body)).retrieve().body(String.class));
			var balance = answer.path("balance");
			if (!"success".equals(answer.path("status").asString("")) || !balance.isNumber()) {
				log.warn("Rancard didn't give the SMS balance: {}", answer.path("message").asString(answer.path("status").asString("no reason given")));
				return OptionalLong.empty();
			}
			return OptionalLong.of(balance.asLong());
		}
		catch (RuntimeException e) {
			log.warn("Couldn't ask Rancard for the SMS balance: {}", e.toString());
			return OptionalLong.empty();
		}
	}

	@Override
	public long lowAt() {
		return lowAt;
	}

}
