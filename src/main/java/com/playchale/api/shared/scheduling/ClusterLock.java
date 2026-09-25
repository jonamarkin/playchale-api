package com.playchale.api.shared.scheduling;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes a scheduled job run on one copy of the API at a time, with a Postgres advisory lock held
 * until the job's transaction ends. Every copy runs the schedule; the ones that don't get the lock
 * skip that turn. No extra tables and nothing to clean up if a copy crashes mid-job.
 */
@Component
public class ClusterLock {

	private final JdbcClient jdbc;

	ClusterLock(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Takes the named lock for the current transaction, if nobody else holds it. Call it inside the job's transaction. */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean tryLock(String name) {
		return jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtext(:name))").param("name", name).query(Boolean.class).single();
	}

}
