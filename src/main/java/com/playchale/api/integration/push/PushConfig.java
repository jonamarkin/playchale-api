package com.playchale.api.integration.push;

import java.net.URI;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Phone notifications are on when both VAPID keys are set (see {@link PushProperties}); then there's
 * a {@link PushSender}. Without them, on a laptop (the dev profile) one logs instead; anywhere else
 * there's none, the web app doesn't offer them, and nothing else changes. Keys that aren't a pair
 * stop the app starting, rather than failing every message quietly.
 */
@Configuration
@EnableConfigurationProperties(PushProperties.class)
class PushConfig {

	private static final Logger log = LoggerFactory.getLogger("push");

	@Bean
	@ConditionalOnExpression("!'${playchale.push.vapid-public-key:}'.isBlank() and !'${playchale.push.vapid-private-key:}'.isBlank()")
	PushSender webPushSender(PushProperties push, Clock clock) throws GeneralSecurityException {
		var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
		log.info("Phone notifications are on");
		return new WebPushSender(http, new Vapid(push), clock);
	}

	/**
	 * On a laptop without keys: a stand-in that logs what it would send, so phone notifications can be
	 * tried end to end without reaching the browsers' push services. Its key is made up at start-up;
	 * browsers never get a real message from it.
	 */
	@Bean
	@Profile("dev")
	@ConditionalOnMissingBean(PushSender.class)
	PushSender loggingPushSender() throws GeneralSecurityException {
		var key = WebPushCrypto.base64url(WebPushCrypto.encodePublic((ECPublicKey) WebPushCrypto.generateKeyPair().getPublic()));
		return new PushSender() {

			@Override
			public String publicKey() {
				return key;
			}

			@Override
			public Delivery send(PushTarget target, String payload) {
				if (!target.trusted()) {
					return Delivery.GONE;
				}
				log.info("Push (dev profile: not sent) to={} payload={}", URI.create(target.endpoint()).getHost(), payload);
				return Delivery.SENT;
			}

		};
	}

}
