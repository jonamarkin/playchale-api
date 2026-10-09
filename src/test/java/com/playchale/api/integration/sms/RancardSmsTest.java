package com.playchale.api.integration.sms;

import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The Rancard adapter against a stand-in for Rancard's API: what it sends, and what a refusal becomes. */
class RancardSmsTest {

	private static final RancardProperties RANCARD = new RancardProperties("not-a-real-key", "PlayChale",
			"https://bulkmessagingapi.rancard.com", "https://unify-base.rancard.com", 500);

	private static final String SENT = """
			{"code":200,"success":true,"message":"SMS request is being processed","result":{"campaignId":"a04c4103","contactSize":1},"error":null}
			""";

	@Test
	void sendsTheTextToTheNumberWithoutItsPlusFromTheApprovedSender() {
		var builder = RestClient.builder();
		var rancard = MockRestServiceServer.bindTo(builder).build();
		var sender = new RancardSmsSender(builder, RANCARD, JsonMapper.builder().build());
		rancard.expect(requestTo("https://bulkmessagingapi.rancard.com/api/v1/sms/public/sendMessage"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Idempotency-Key", org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")))
			.andExpect(jsonPath("$.apiKey").value("not-a-real-key"))
			.andExpect(jsonPath("$.senderId").value("PlayChale"))
			.andExpect(jsonPath("$.message").value("Your PlayChale code is 123456"))
			.andExpect(jsonPath("$.contacts[0]").value("233241234567"))
			.andExpect(jsonPath("$.scheduled").value(false))
			.andRespond(withSuccess(SENT, MediaType.APPLICATION_JSON));

		sender.send("+233241234567", "Your PlayChale code is 123456");

		rancard.verify();
	}

	@Test
	void aRefusalTellsThePersonToTryAgainEvenWhenRancardAnswers200() {
		var builder = RestClient.builder();
		var rancard = MockRestServiceServer.bindTo(builder).build();
		var sender = new RancardSmsSender(builder, RANCARD, JsonMapper.builder().build());
		rancard.expect(requestTo("https://bulkmessagingapi.rancard.com/api/v1/sms/public/sendMessage"))
			.andRespond(withSuccess("{\"code\":400,\"success\":false,\"message\":\"Invalid regular request.\"}", MediaType.APPLICATION_JSON));
		rancard.expect(requestTo("https://bulkmessagingapi.rancard.com/api/v1/sms/public/sendMessage"))
			.andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
				.body("{\"code\":401,\"success\":false,\"message\":\"Invalid API key\"}"));

		for (int i = 0; i < 2; i++) {
			assertThatThrownBy(() -> sender.send("+233241234567", "Your PlayChale code is 123456"))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT);
					assertThat(e.getMessage()).isEqualTo("We couldn’t send the text just now. Please try again in a minute.");
				});
		}
		rancard.verify();
	}

	@Test
	void aSenderIdRancardWouldRefuseStopsTheAppStarting() {
		assertThatThrownBy(() -> new RancardSmsSender(RestClient.builder(),
				new RancardProperties("key", "PlayChaleGhana", "https://x", "https://y", 500), JsonMapper.builder().build()))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("11 characters");
		assertThatThrownBy(() -> new RancardSmsSender(RestClient.builder(), new RancardProperties("key", " ", "https://x", "https://y", 500),
				JsonMapper.builder().build()))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void theBalanceIsTheBundlesCreditsOrNothingWhenRancardCantSay() {
		var builder = RestClient.builder();
		var rancard = MockRestServiceServer.bindTo(builder).build();
		var balance = new RancardSmsBalance(builder, RANCARD, JsonMapper.builder().build());
		rancard.expect(requestTo("https://unify-base.rancard.com/sms/balance"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(jsonPath("$.apiKey").value("not-a-real-key"))
			.andRespond(withSuccess("{\"status\":\"success\",\"balance\":15420}", MediaType.APPLICATION_JSON));
		rancard.expect(requestTo("https://unify-base.rancard.com/sms/balance"))
			.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		assertThat(balance.credits()).hasValue(15420);
		assertThat(balance.credits()).isEmpty();
		assertThat(balance.lowAt()).isEqualTo(500);
		rancard.verify();
	}

	@Test
	void numbersInTheLogHaveTheirMiddleHidden() {
		assertThat(RancardSmsSender.masked("233241234567")).isEqualTo("23324•••4567");
	}

}
