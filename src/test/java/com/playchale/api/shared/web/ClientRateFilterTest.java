package com.playchale.api.shared.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** How fast one address can ask, without a server. */
class ClientRateFilterTest {

	@Test
	void aBurstThenTheSteadyRate() {
		var bucket = new ClientRateFilter.Bucket(0);
		int allowed = 0;
		for (int i = 0; i < 100; i++) {
			allowed += bucket.take(0) ? 1 : 0;
		}
		assertThat(allowed).as("a burst, all at once").isEqualTo((int) ClientRateFilter.BURST);
		// A second later, a second's worth more.
		allowed = 0;
		for (int i = 0; i < 100; i++) {
			allowed += bucket.take(1_000_000_000L) ? 1 : 0;
		}
		assertThat(allowed).isEqualTo((int) ClientRateFilter.PER_SECOND);
	}

	@Test
	void anIpv6AddressCountsByItsSlash64SoRotatingWithinItDoesntHelp() {
		assertThat(ClientRateFilter.key("2001:db8:1:2:3:4:5:6")).isEqualTo(ClientRateFilter.key("2001:db8:1:2:ffff::1"));
		assertThat(ClientRateFilter.key("2001:db8:1:2::1")).isNotEqualTo(ClientRateFilter.key("2001:db8:1:3::1"));
		assertThat(ClientRateFilter.key("203.0.113.7")).isEqualTo("203.0.113.7");
	}

}
