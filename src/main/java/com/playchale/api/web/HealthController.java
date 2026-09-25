package com.playchale.api.web;

import com.playchale.api.config.PlaychaleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers load balancers and uptime checks. It checks the database too, so "healthy" means "can
 * actually serve requests", not just "the process is running".
 */
@RestController
class HealthController {

	private static final Logger log = LoggerFactory.getLogger(HealthController.class);

	record Health(String status, String version, String env, String database) {
	}

	private final JdbcClient jdbc;

	private final PlaychaleProperties properties;

	private final String version;

	HealthController(JdbcClient jdbc, PlaychaleProperties properties, @Value("${playchale.version:dev}") String version) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.version = version;
	}

	@GetMapping("/healthz")
	ResponseEntity<Health> health() {
		try {
			jdbc.sql("SELECT 1").query(Integer.class).single();
			return ResponseEntity.ok(new Health("ok", version, properties.env(), "ok"));
		}
		catch (RuntimeException e) {
			log.error("health check: database unreachable", e);
			return ResponseEntity.status(503).body(new Health("degraded", version, properties.env(), "unreachable"));
		}
	}

}
