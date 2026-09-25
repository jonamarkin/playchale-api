package com.playchale.api.integration.payments;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.playchale.api.shared.TestClock;
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

/** The Paystack adapter against a stand-in for Paystack's API: what it sends, and how it reads the answers. */
class PaystackPaymentProviderTest {

	private static final String SECRET = "sk_test_not_a_real_key";

	private static final Instant STARTED = Instant.parse("2026-09-25T08:00:00Z");

	private final TestClock clock = new TestClock();

	private MockRestServiceServer paystack;

	private PaystackPaymentProvider provider;

	private final PaymentProvider.Charge charge = new PaymentProvider.Charge("PC-7K2M9QXA", "momo-mtn", 2500, "GHS", null,
			"kwame@example.com", "https://playchale.com/games/g1?payment=p1");

	@BeforeEach
	void setUp() {
		var builder = RestClient.builder();
		paystack = MockRestServiceServer.bindTo(builder).build();
		provider = new PaystackPaymentProvider(builder, new PaystackProperties(SECRET, "https://api.paystack.co"), JsonMapper.builder().build(),
				clock);
		clock.set(STARTED.plusSeconds(60));
	}

	private void verifyAnswers(String status, long amount) {
		paystack.expect(requestTo("https://api.paystack.co/transaction/verify/PC-7K2M9QXA"))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess("""
					{"status": true, "message": "Verification successful",
					 "data": {"status": "%s", "reference": "PC-7K2M9QXA", "amount": %d, "currency": "GHS"}}
					""".formatted(status, amount), MediaType.APPLICATION_JSON));
	}

	@Test
	void startingAPaymentOpensAHostedCheckout() {
		paystack.expect(requestTo("https://api.paystack.co/transaction/initialize"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", "Bearer " + SECRET))
			.andExpect(jsonPath("$.email").value("kwame@example.com"))
			.andExpect(jsonPath("$.amount").value(2500))
			.andExpect(jsonPath("$.currency").value("GHS"))
			.andExpect(jsonPath("$.reference").value("PC-7K2M9QXA"))
			.andExpect(jsonPath("$.channels[0]").value("mobile_money"))
			.andExpect(jsonPath("$.callback_url").value("https://playchale.com/games/g1?payment=p1"))
			.andRespond(withSuccess("""
					{"status": true, "message": "Authorization URL created",
					 "data": {"authorization_url": "https://checkout.paystack.com/abc123", "access_code": "abc123", "reference": "PC-7K2M9QXA"}}
					""", MediaType.APPLICATION_JSON));

		assertThat(provider.charge(charge).checkoutUrl()).isEqualTo("https://checkout.paystack.com/abc123");
		paystack.verify();
	}

	@Test
	void aRefusalReachesThePayerInPlainWords() {
		paystack.expect(requestTo("https://api.paystack.co/transaction/initialize"))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body("{\"status\": false, \"message\": \"Invalid email address passed\"}"));

		assertThatThrownBy(() -> provider.charge(charge)).isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYMENT_FAILED));
	}

	@Test
	void paystacksVerdictIsTheAnswer() {
		verifyAnswers("success", 2500);
		assertThat(provider.check(charge, STARTED).state()).isEqualTo(PaymentProvider.State.SUCCEEDED);
	}

	@Test
	void aSuccessForAnotherAmountIsLeftForAPerson() {
		verifyAnswers("success", 100);
		assertThat(provider.check(charge, STARTED).state()).isEqualTo(PaymentProvider.State.PENDING);
	}

	@Test
	void declinesAndReversalsAreFailures() {
		verifyAnswers("failed", 2500);
		assertThat(provider.check(charge, STARTED).failureReason()).isEqualTo("The payment was declined. No money was taken.");
	}

	@Test
	void anAbandonedCheckoutIsOnlyAFailureOnceThePayerHasHadTime() {
		verifyAnswers("abandoned", 2500);
		assertThat(provider.check(charge, STARTED).state()).as("may still be on the checkout page").isEqualTo(PaymentProvider.State.PENDING);

		paystack.reset();
		clock.set(STARTED.plus(PaystackPaymentProvider.UNFINISHED_AFTER).plus(Duration.ofMinutes(1)));
		verifyAnswers("abandoned", 2500);
		assertThat(provider.check(charge, STARTED).state()).isEqualTo(PaymentProvider.State.FAILED);
	}

	@Test
	void onlySignedWebhooksAreBelieved() {
		var body = "{\"event\":\"charge.success\",\"data\":{\"reference\":\"PC-7K2M9QXA\",\"status\":\"success\"}}";
		assertThat(provider.webhookReference(body, sign(body))).contains("PC-7K2M9QXA");
		assertThat(provider.webhookReference(body, sign(body + " "))).as("signature over other bytes").isEmpty();
		assertThat(provider.webhookReference(body, null)).isEmpty();
		var transfer = "{\"event\":\"transfer.success\",\"data\":{\"reference\":\"T-1\"}}";
		assertThat(provider.webhookReference(transfer, sign(transfer))).as("not about a charge").isEmpty();
	}

	static String sign(String body) {
		try {
			var mac = Mac.getInstance("HmacSHA512");
			mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
			return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

}
