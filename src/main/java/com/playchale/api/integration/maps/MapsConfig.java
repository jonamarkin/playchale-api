package com.playchale.api.integration.maps;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Which {@link PlaceLocator} runs: Google's whenever its server key is set, otherwise none. The only
 * place that knows the provider; {@link PinRefresh} asks for a {@link PlaceLocator}.
 */
@Configuration
@EnableConfigurationProperties(GoogleMapsProperties.class)
class MapsConfig {

	@Bean
	@ConditionalOnExpression("!'${playchale.google.maps.key:}'.isBlank()")
	PlaceLocator googlePlaceLocator(GoogleMapsProperties properties, ObjectMapper json) {
		var requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
		requests.setReadTimeout(Duration.ofSeconds(10));
		return new GooglePlaceLocator(RestClient.builder().requestFactory(requests), properties, json);
	}

}
