package com.playchale.api.integration.sms;

import java.net.http.HttpClient;
import java.time.Duration;

import com.playchale.api.shared.scheduling.ClusterLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Which {@link SmsSender} runs: Rancard whenever its API key is set; otherwise, on a laptop (the dev
 * profile), one that writes texts to the log. Anywhere else without a key there's none, and signing
 * in by phone isn't offered.
 *
 * <p>This is the only place that knows the provider. Everything else asks for {@link SmsSender} (and
 * {@link SmsBalance}), so another provider is one more adapter class and its beans here, switched on
 * by its own key.
 */
@Configuration
@EnableConfigurationProperties(RancardProperties.class)
class SmsConfig {

	private static final Logger log = LoggerFactory.getLogger("sms");

	/** Someone is waiting for their code, so Rancard gets a few seconds, not minutes. */
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

	private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

	private static RestClient.Builder client() {
		var requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
		requests.setReadTimeout(READ_TIMEOUT);
		return RestClient.builder().requestFactory(requests);
	}

	@Bean
	@ConditionalOnExpression("!'${playchale.rancard.api-key:}'.isBlank()")
	SmsSender rancardSmsSender(RancardProperties rancard, ObjectMapper json) {
		return new RancardSmsSender(client(), rancard, json);
	}

	@Bean
	@ConditionalOnExpression("!'${playchale.rancard.api-key:}'.isBlank()")
	SmsBalance rancardSmsBalance(RancardProperties rancard, ObjectMapper json) {
		return new RancardSmsBalance(client(), rancard, json);
	}

	/** Whichever provider is on the other end, a low bundle is warned about. */
	@Bean
	@ConditionalOnBean(SmsBalance.class)
	SmsBalanceWatch smsBalanceWatch(SmsBalance balance, ClusterLock lock) {
		return new SmsBalanceWatch(balance, lock);
	}

	/** On a laptop, texts go to the log instead of a phone. */
	@Bean
	@Profile("dev")
	@ConditionalOnMissingBean(SmsSender.class)
	SmsSender loggingSmsSender() {
		return (phone, message) -> log.info("SMS (dev profile: not sent) to={} message={}", phone, message);
	}

}
