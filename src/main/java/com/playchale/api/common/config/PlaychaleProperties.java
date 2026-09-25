package com.playchale.api.config;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The API's own settings, from the {@code playchale.*} keys in application.yml or the matching
 * environment variables (PLAYCHALE_SECRET and so on). Validated at startup, so a bad setting stops
 * the app before it serves anyone.
 *
 * @param env              {@code development} or {@code production}
 * @param corsOrigins      web origins allowed to call the API from a browser, e.g. the web app
 * @param secret           keys the hashes of sign-in codes
 * @param demoSignInCode   development only: every sign-in code is this, and it's returned to the app
 */
@Validated
@ConfigurationProperties("playchale")
public record PlaychaleProperties(
		@NotBlank @Pattern(regexp = "development|production", message = "must be development or production") String env,
		List<String> corsOrigins,
		@NotBlank String secret,
		@Pattern(regexp = "^$|^\\d{6}$", message = "must be six digits") String demoSignInCode) {

	public PlaychaleProperties {
		corsOrigins = corsOrigins == null ? List.of() : List.copyOf(corsOrigins);
		demoSignInCode = demoSignInCode == null ? "" : demoSignInCode;
	}

	public boolean isProduction() {
		return "production".equals(env);
	}

	public boolean hasDemoCode() {
		return !demoSignInCode.isEmpty();
	}

}
