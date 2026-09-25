package com.playchale.api.market;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketTest {

	private final Market ghana = Market.get("GH");

	/** A parameterised test: each row is one case, the Java equivalent of Go's table-driven tests. */
	@ParameterizedTest(name = "{0} → {1}")
	@CsvSource(nullValues = "none", value = {
			"024 123 4567,      +233241234567",
			"0241234567,        +233241234567",
			"241234567,         +233241234567",
			"+233 24 123 4567,  +233241234567",
			"233241234567,      +233241234567",
			"020 555 1234,      +233205551234",
			"012 345 6789,      none",   // Ghana mobile numbers never start with 1
			"024 123,           none",
	})
	void normalisesPhoneNumbers(String input, String expected) {
		assertThat(ghana.normalisePhone(input).orElse(null)).isEqualTo(expected);
	}

	@Test
	void unknownCountriesFallBackToTheDefault() {
		assertThat(Market.get("ZZ").country()).isEqualTo(Market.DEFAULT);
	}

}
