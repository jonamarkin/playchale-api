package com.playchale.api.usage.internal.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Where a visit came from, tidied so the same place always reads the same. */
class UsageSourceTest {

	@Test
	void aSiteIsItsOwnNameWithoutTheParts() {
		assertThat(UsageCounts.source("https://l.facebook.com/l.php?u=x")).isEqualTo("facebook.com");
		assertThat(UsageCounts.source("m.facebook.com")).isEqualTo("facebook.com");
		assertThat(UsageCounts.source("https://web.whatsapp.com/")).isEqualTo("whatsapp.com");
		assertThat(UsageCounts.source("www.google.com.gh")).isEqualTo("google.com.gh");
		assertThat(UsageCounts.source("news.bbc.co.uk")).isEqualTo("bbc.co.uk");
	}

	@Test
	void aTagIsKeptReadable() {
		assertThat(UsageCounts.source("hillview")).isEqualTo("hillview");
		assertThat(UsageCounts.source("Hillview Chapel")).isEqualTo("hillview-chapel");
		assertThat(UsageCounts.source("direct")).isEqualTo("direct");
		assertThat(UsageCounts.source("x".repeat(90))).hasSize(60);
	}

	@Test
	void nothingIsBlankAndNonsenseIsNotCounted() {
		assertThat(UsageCounts.source(null)).isEmpty();
		assertThat(UsageCounts.source("  ")).isEmpty();
		assertThat(UsageCounts.source("!!!")).isNull();
	}

}
