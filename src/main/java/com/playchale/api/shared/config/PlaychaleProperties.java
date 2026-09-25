package com.playchale.api.shared.config;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The API's own settings, from the {@code playchale.*} keys in application.yml (and
 * application-dev.yml on a laptop) or the matching environment variables (PLAYCHALE_SECRET and so
 * on). Validated at startup, so a bad setting stops the app before it serves anyone.
 *
 * <p>The defaults are the production ones. The {@code dev} profile relaxes them.
 *
 * @param corsOrigins      web origins allowed to call the API from a browser, e.g. the web app
 * @param secret           keys the hashes of sign-in codes
 * @param demoSignInCode   dev only: every sign-in code is this, and it's returned to the app
 * @param secureCookies    send the session cookie over HTTPS only. Off only on a laptop's plain http
 * @param testSupport      dev only: endpoints under /dev that reset and seed the database
 */
@Validated
@ConfigurationProperties("playchale")
public record PlaychaleProperties(
		List<String> corsOrigins,
		@NotBlank(message = "must be set, e.g. PLAYCHALE_SECRET=$(openssl rand -hex 32)") String secret,
		@Pattern(regexp = "^$|^\\d{6}$", message = "must be six digits") String demoSignInCode,
		@DefaultValue("true") boolean secureCookies,
		boolean testSupport) {

	public PlaychaleProperties {
		corsOrigins = corsOrigins == null ? List.of() : List.copyOf(corsOrigins);
		demoSignInCode = demoSignInCode == null ? "" : demoSignInCode;
	}

	public boolean hasDemoCode() {
		return !demoSignInCode.isEmpty();
	}

}
