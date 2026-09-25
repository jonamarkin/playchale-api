package com.playchale.api.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Refuses to start in production with settings that are only safe on a laptop. Failing at startup
 * is the point: a misconfigured deploy never serves a single request.
 */
@Component
class ProductionSafety implements InitializingBean {

	static final String DEVELOPMENT_SECRET = "development-only-secret-do-not-use-in-production";

	private final PlaychaleProperties properties;

	ProductionSafety(PlaychaleProperties properties) {
		this.properties = properties;
	}

	@Override
	public void afterPropertiesSet() {
		if (!properties.isProduction()) {
			return;
		}
		if (properties.secret().length() < 32 || DEVELOPMENT_SECRET.equals(properties.secret())) {
			throw new IllegalStateException("PLAYCHALE_SECRET must be a random value of at least 32 characters in production");
		}
		if (properties.hasDemoCode()) {
			throw new IllegalStateException("PLAYCHALE_DEMO_SIGN_IN_CODE must be empty in production");
		}
	}

}
