package com.playchale.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real Postgres in a throwaway container for tests, the same version as development and
 * production. {@code @ServiceConnection} points the app's datasource at it automatically.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	/**
	 * Postgres 17, the official image, from Google's mirror of Docker's official images rather than
	 * Docker Hub or AWS's public mirror: both limit anonymous pulls per IP address, which CI's shared
	 * machines run out of.
	 */
	public static final DockerImageName POSTGRES = DockerImageName.parse("mirror.gcr.io/library/postgres:17")
		.asCompatibleSubstituteFor("postgres");


}
