package com.playchale.api.auth.internal.service;

import java.net.MalformedURLException;
import java.net.URI;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GoogleSignInProperties.class)
class GoogleConfig {

	/** Google's public signing keys: fetched on first use, cached, and fetched again when Google rotates them. */
	@Bean
	JWKSource<SecurityContext> googleSigningKeys() throws MalformedURLException {
		return JWKSourceBuilder.<SecurityContext>create(URI.create("https://www.googleapis.com/oauth2/v3/certs").toURL()).retrying(true).build();
	}

}
