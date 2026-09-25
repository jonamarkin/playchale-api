package com.playchale.api.shared;

import java.util.List;

import com.playchale.api.shared.config.PlaychaleProperties;
import com.playchale.api.shared.config.ProductionSafety;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/** Outside the dev profile, laptop-only settings stop the app from starting. */
class ProductionSafetyTest {

	private static final String REAL_SECRET = "0123456789abcdef0123456789abcdef";

	private static final List<String> WEB_APP = List.of("https://playchale.com");

	private static void check(String secret, String demoCode, boolean secureCookies, boolean testSupport, List<String> origins) {
		new ProductionSafety(new PlaychaleProperties(origins, secret, demoCode, secureCookies, testSupport)).afterPropertiesSet();
	}

	@Test
	void startsWithRealSettings() {
		assertThatCode(() -> check(REAL_SECRET, "", true, false, WEB_APP)).doesNotThrowAnyException();
	}

	@Test
	void refusesTheDevelopmentSecret() {
		assertThatIllegalStateException().isThrownBy(() -> check(ProductionSafety.DEVELOPMENT_SECRET, "", true, false, WEB_APP));
	}

	@Test
	void refusesAShortSecret() {
		assertThatIllegalStateException().isThrownBy(() -> check("short", "", true, false, WEB_APP));
	}

	@Test
	void refusesDemoCodes() {
		assertThatIllegalStateException().isThrownBy(() -> check(REAL_SECRET, "123456", true, false, WEB_APP));
	}

	@Test
	void refusesTestSupport() {
		assertThatIllegalStateException().isThrownBy(() -> check(REAL_SECRET, "", true, true, WEB_APP));
	}

	@Test
	void refusesInsecureCookies() {
		assertThatIllegalStateException().isThrownBy(() -> check(REAL_SECRET, "", false, false, WEB_APP));
	}

	@Test
	void refusesMissingWebAppOrigin() {
		assertThatIllegalStateException().isThrownBy(() -> check(REAL_SECRET, "", true, false, List.of()));
	}

}
