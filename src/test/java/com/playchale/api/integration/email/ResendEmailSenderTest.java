package com.playchale.api.integration.email;

import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.shared.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
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

/** The Resend adapter against a stand-in for Resend's API: what it sends, and what a refusal becomes. */
class ResendEmailSenderTest {

	private static final String KEY = "re_not_a_real_key";

	private MockRestServiceServer resend;

	private ResendEmailSender sender;

	@BeforeEach
	void setUp() {
		var builder = RestClient.builder();
		resend = MockRestServiceServer.bindTo(builder).build();
		sender = new ResendEmailSender(builder, new ResendProperties(KEY, "PlayChale <alert@playchale.com>", "https://api.resend.com"),
				JsonMapper.builder().build());
	}

	@Test
	void sendsAPlainTextEmailFromTheConfiguredSender() {
		resend.expect(requestTo("https://api.resend.com/emails"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", "Bearer " + KEY))
			.andExpect(jsonPath("$.from").value("PlayChale <alert@playchale.com>"))
			.andExpect(jsonPath("$.to[0]").value("ama@example.com"))
			.andExpect(jsonPath("$.subject").value("Your PlayChale sign-in code: 123456"))
			.andExpect(jsonPath("$.text").value("Your PlayChale code is 123456."))
			.andExpect(jsonPath("$.html").value("<p>Your PlayChale code is <b>123456</b>.</p>"))
			.andRespond(withSuccess("{\"id\": \"49a3999c-0ce1-4ea6-ab68-afcd6dc2e794\"}", MediaType.APPLICATION_JSON));

		sender.send(new Email("ama@example.com", "Your PlayChale sign-in code: 123456", "Your PlayChale code is 123456.",
				"<p>Your PlayChale code is <b>123456</b>.</p>"));

		resend.verify();
	}

	@Test
	void aRefusalTellsThePersonToTryAgain() {
		resend.expect(requestTo("https://api.resend.com/emails"))
			.andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT).contentType(MediaType.APPLICATION_JSON)
				.body("{\"statusCode\": 422, \"name\": \"validation_error\", \"message\": \"The playchale.com domain is not verified.\"}"));

		assertThatThrownBy(() -> sender.send(new Email("ama@example.com", "Subject", "Text", null)))
			.isInstanceOfSatisfying(BusinessException.class, e -> {
				assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT);
				assertThat(e.getMessage()).isEqualTo("We couldn’t send the email just now. Please try again in a minute.");
			});
	}

	@Test
	void refusesToStartWithoutASender() {
		assertThatThrownBy(() -> new ResendEmailSender(RestClient.builder(), new ResendProperties(KEY, " ", "https://api.resend.com"),
				JsonMapper.builder().build()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("PLAYCHALE_RESEND_FROM");
	}

}
