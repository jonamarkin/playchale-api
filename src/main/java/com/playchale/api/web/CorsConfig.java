package com.playchale.api.web;

import com.playchale.api.config.PlaychaleProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets the web app, served from a different origin, call the API from the browser with its session
 * cookie. Only the configured origins are allowed; the browser blocks everyone else.
 */
@Configuration
class CorsConfig implements WebMvcConfigurer {

	private final PlaychaleProperties properties;

	CorsConfig(PlaychaleProperties properties) {
		this.properties = properties;
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/**")
			.allowedOrigins(properties.corsOrigins().toArray(String[]::new))
			.allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
			.allowedHeaders("Content-Type", "Authorization", RequestLogFilter.HEADER)
			.exposedHeaders(RequestLogFilter.HEADER)
			.allowCredentials(true)
			.maxAge(600);
	}

}
