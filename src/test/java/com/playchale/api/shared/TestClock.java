package com.playchale.api.shared;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests can move forward, to check expiry without waiting. */
public class TestClock extends Clock {

	private Instant now = Instant.now();

	public void advance(Duration by) {
		now = now.plus(by);
	}

	public void set(Instant instant) {
		now = instant;
	}

	@Override
	public Instant instant() {
		return now;
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}

}
