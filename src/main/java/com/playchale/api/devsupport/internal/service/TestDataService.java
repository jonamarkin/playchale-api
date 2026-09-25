package com.playchale.api.devsupport.internal.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test data for the web app's end-to-end tests. Only exists with playchale.test-support on, which
 * only the dev profile does; ProductionSafety refuses to start a deploy with it on.
 */
@Service
@ConditionalOnBooleanProperty("playchale.test-support")
public class TestDataService {

	private final JdbcClient jdbc;

	TestDataService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Empties every table (but not Flyway's record of migrations), so each test starts clean. */
	@Transactional
	public void reset() {
		var tables = jdbc.sql("""
				SELECT quote_ident(tablename) FROM pg_tables
				WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
				""").query(String.class).list();
		jdbc.sql("TRUNCATE " + String.join(", ", tables) + " CASCADE").update();
	}

}
