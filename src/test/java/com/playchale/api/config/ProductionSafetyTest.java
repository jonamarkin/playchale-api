package com.playchale.api.config;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ProductionSafetyTest {

	private static final String REAL_SECRET = "0123456789abcdef0123456789abcdef";

	private static ProductionSafety check(String env, String secret, String demoCode) {
		return new ProductionSafety(new PlaychaleProperties(env, List.of(), secret, demoCode));
	}

	@Test
	void developmentAllowsTheDefaults() {
		assertThatCode(() -> check("development", ProductionSafety.DEVELOPMENT_SECRET, "123456").afterPropertiesSet())
			.doesNotThrowAnyException();
	}

	@Test
	void productionRefusesTheDevelopmentSecret() {
		assertThatIllegalStateException()
			.isThrownBy(() -> check("production", ProductionSafety.DEVELOPMENT_SECRET, "").afterPropertiesSet());
	}

	@Test
	void productionRefusesAShortSecret() {
		assertThatIllegalStateException().isThrownBy(() -> check("production", "short", "").afterPropertiesSet());
	}

	@Test
	void productionRefusesDemoCodes() {
		assertThatIllegalStateException().isThrownBy(() -> check("production", REAL_SECRET, "123456").afterPropertiesSet());
	}

	@Test
	void productionStartsWithRealSettings() {
		assertThatCode(() -> check("production", REAL_SECRET, "").afterPropertiesSet()).doesNotThrowAnyException();
	}

}
