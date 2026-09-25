package com.playchale.api.devsupport.internal.service;

import java.util.List;

import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.users.api.UserSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo data for a laptop and the web app's end-to-end tests. Only exists with playchale.test-support
 * on, which only the dev profile does; ProductionSafety refuses to start a deploy with it on.
 */
@Service
@ConditionalOnBooleanProperty("playchale.test-support")
public class TestDataService {

	private static final Logger log = LoggerFactory.getLogger(TestDataService.class);

	/** One of the accounts the sign-in page offers, as the web app's demoAccounts() returns them. */
	public record DemoAccount(UserSummary user, String note) {
	}

	private final JdbcClient jdbc;

	private final DemoSeed seed;

	private final UserDirectory users;

	TestDataService(JdbcClient jdbc, DemoSeed seed, UserDirectory users) {
		this.jdbc = jdbc;
		this.seed = seed;
		this.users = users;
	}

	/** Empties every table (but not Flyway's record of migrations) and loads the demo data again, so each test starts the same. */
	@Transactional
	public void reset() {
		var tables = jdbc.sql("""
				SELECT quote_ident(tablename) FROM pg_tables
				WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
				""").query(String.class).list();
		jdbc.sql("TRUNCATE " + String.join(", ", tables) + " CASCADE").update();
		seed.load();
	}

	/** The seeded players the sign-in page offers, with their demo numbers. */
	@Transactional(readOnly = true)
	public List<DemoAccount> demoAccounts() {
		var found = users.findAll(DemoSeed.DEMO_ACCOUNTS.stream().map(a -> SeedIds.of(a.mockId())).toList());
		return DemoSeed.DEMO_ACCOUNTS.stream()
			.filter(a -> found.containsKey(SeedIds.of(a.mockId())))
			.map(a -> new DemoAccount(found.get(SeedIds.of(a.mockId())), a.note()))
			.toList();
	}

	/** A laptop starting on an empty database gets the demo data, so the web app has something to show. */
	@EventListener(ApplicationReadyEvent.class)
	@Transactional
	public void seedAnEmptyDatabase() {
		var empty = jdbc.sql("SELECT NOT EXISTS (SELECT 1 FROM users)").query(Boolean.class).single();
		if (empty) {
			seed.load();
			log.info("Loaded the demo data into an empty database (dev profile)");
		}
	}

}
