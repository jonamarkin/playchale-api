package com.playchale.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The PlayChale API.
 *
 * <p>Run it with {@code ./mvnw spring-boot:run}: Spring Boot starts Postgres from compose.yaml,
 * applies the Flyway migrations and serves on port 8080.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PlaychaleApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(PlaychaleApiApplication.class, args);
	}

}
