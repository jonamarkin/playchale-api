package com.playchale.api.integration.push;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Reading Expo's answers, and which tokens are the app's. */
class ExpoPushSenderTest {

	private final JsonMapper json = JsonMapper.builder().build();

	@Test
	void anAnswerSaysSentGoneOrTryAgain() {
		assertThat(ExpoPushSender.delivery(json.readTree("{\"data\":{\"status\":\"ok\",\"id\":\"abc\"}}"))).isEqualTo(PushSender.Delivery.SENT);
		assertThat(ExpoPushSender.delivery(json.readTree("{\"data\":[{\"status\":\"ok\"}]}"))).isEqualTo(PushSender.Delivery.SENT);
		assertThat(ExpoPushSender.delivery(json.readTree(
				"{\"data\":{\"status\":\"error\",\"message\":\"gone\",\"details\":{\"error\":\"DeviceNotRegistered\"}}}")))
			.isEqualTo(PushSender.Delivery.GONE);
		assertThat(ExpoPushSender.delivery(json.readTree(
				"{\"data\":{\"status\":\"error\",\"message\":\"too big\",\"details\":{\"error\":\"MessageTooBig\"}}}")))
			.isEqualTo(PushSender.Delivery.FAILED);
	}

	@Test
	void onlyTheAppsTokensAreTokens() {
		assertThat(AppPushSender.isToken("ExponentPushToken[xxxxxxxxxxxxxxxxxxxxxx]")).isTrue();
		assertThat(AppPushSender.isToken("ExpoPushToken[abc-123_DEF]")).isTrue();
		assertThat(AppPushSender.isToken("https://fcm.googleapis.com/fcm/send/abc")).isFalse();
		assertThat(AppPushSender.isToken("ExponentPushToken[<script>]")).isFalse();
		assertThat(AppPushSender.isToken(null)).isFalse();
	}

}
