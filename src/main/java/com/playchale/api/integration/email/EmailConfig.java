package com.playchale.api.integration.email;

import java.net.http.HttpClient;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Which {@link EmailSender} runs: Resend whenever its API key is set; otherwise, on a laptop (the
 * dev profile), one that writes emails to the log. Anywhere else without a key there's none, and
 * signing in by email isn't offered.
 */
@Configuration
@EnableConfigurationProperties(ResendProperties.class)
class EmailConfig {

	private static final Logger log = LoggerFactory.getLogger("email");

	/** Someone is waiting for their code, so Resend gets a few seconds, not minutes. */
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

	private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

	@Bean
	@ConditionalOnExpression("!'${playchale.resend.api-key:}'.isBlank()")
	EmailSender resendEmailSender(ResendProperties resend, ObjectMapper json) {
		var requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
		requests.setReadTimeout(READ_TIMEOUT);
		return new ResendEmailSender(RestClient.builder().requestFactory(requests), resend, json);
	}

	@Bean
	@Profile("dev")
	@ConditionalOnMissingBean(EmailSender.class)
	EmailSender loggingEmailSender() {
		return email -> log.info("Email (dev profile: not sent) to={} subject={} text={}", email.to(), email.subject(), email.text());
	}

}
