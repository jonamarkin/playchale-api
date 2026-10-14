package com.playchale.api;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A new venue waits for PlayChale's staff before players can find or book it (VenueReview). Tests
 * about finding and booking venues start from venues staff have already approved.
 */
public final class TestVenues {

	private TestVenues() {
	}

	/** Every venue still waiting, live now: as if staff had approved them. */
	public static void approveAll(JdbcClient jdbc) {
		jdbc.sql("UPDATE venues SET review = 'approved', reviewed_at = now() WHERE review = 'pending'").update();
	}

}
