package com.playchale.api.shared.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Anywhere the {@code dev} profile is off (every deploy), refuses to start with settings that are
 * only safe on a laptop. Failing at startup is the point: a misconfigured deploy never serves a
 * single request.
 */
@Component
@Profile("!dev")
public class ProductionSafety implements InitializingBean {

	public static final String DEVELOPMENT_SECRET = "development-only-secret-do-not-use-in-production";

	private final PlaychaleProperties properties;

	public ProductionSafety(PlaychaleProperties properties) {
		this.properties = properties;
	}

	@Override
	public void afterPropertiesSet() {
		if (properties.secret().length() < 32 || DEVELOPMENT_SECRET.equals(properties.secret())) {
			throw new IllegalStateException("PLAYCHALE_SECRET must be a random value of at least 32 characters");
		}
		if (properties.hasDemoCode()) {
			throw new IllegalStateException("PLAYCHALE_DEMO_SIGN_IN_CODE must be empty outside the dev profile");
		}
		if (properties.testSupport()) {
			throw new IllegalStateException("PLAYCHALE_TEST_SUPPORT must be off outside the dev profile");
		}
		if (!properties.secureCookies()) {
			throw new IllegalStateException("PLAYCHALE_SECURE_COOKIES must be on outside the dev profile");
		}
		if (properties.corsOrigins().isEmpty()) {
			throw new IllegalStateException("PLAYCHALE_CORS_ORIGINS must name the web app's origin, e.g. https://playchale.com");
		}
	}

}
