package com.playchale.api.shared.security;

import com.playchale.api.shared.config.PlaychaleProperties;
import com.playchale.api.shared.web.RequestLogFilter;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Which browsers may call the API, and how. */
@Configuration
class CorsConfig implements WebMvcConfigurer {

	private final PlaychaleProperties properties;

	CorsConfig(PlaychaleProperties properties) {
		this.properties = properties;
	}

	/**
	 * Lets the web app, served from a different origin, call the API from the browser with its
	 * session cookie. Only the configured origins are allowed.
	 *
	 * <p>This also stops other sites making a signed-in player's browser change things (cross-site
	 * request forgery): Spring refuses any request whose Origin header isn't listed here with 403,
	 * before it reaches a controller. Requests with no Origin (a payment provider's webhook, curl)
	 * don't come from a browser page and pass.
	 */
	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/**")
			.allowedOrigins(properties.corsOrigins().toArray(String[]::new))
			.allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
			.allowedHeaders("Content-Type", RequestLogFilter.HEADER)
			.exposedHeaders(RequestLogFilter.HEADER)
			.allowCredentials(true)
			.maxAge(600);
	}

}
