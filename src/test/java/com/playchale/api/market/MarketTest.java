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
		assertThat(Market.exists("ZZ")).isFalse();
		assertThat(Market.exists("gb")).isTrue();
	}

	/** Any country, from ISO data and libphonenumber: nobody typed these in. */
	@Test
	void anyCountryHasItsCurrencyAndPhoneNumbers() {
		var uk = Market.get("GB");
		assertThat(uk.countryName()).isEqualTo("United Kingdom");
		assertThat(uk.currency()).isEqualTo("GBP");
		assertThat(uk.currencySymbol()).isEqualTo("£");
		assertThat(uk.dialCode()).isEqualTo("+44");
		assertThat(uk.normalisePhone("07400 123456")).contains("+447400123456");
		assertThat(uk.normalisePhone("020 7946 0018")).as("a London landline, not a mobile").isEmpty();
		assertThat(uk.formatMoney(500)).isEqualTo("£ 5");
		assertThat(uk.shareOf(1_000, 3)).as("£3.333 rounds up to the next 10p").isEqualTo(340);

		var nigeria = Market.get("NG");
		assertThat(nigeria.currency()).isEqualTo("NGN");
		assertThat(nigeria.normalisePhone("0803 123 4567")).contains("+2348031234567");

		var us = Market.get("US");
		assertThat(us.normalisePhone("(201) 555-0123")).as("US numbers can be mobile or landline").contains("+12015550123");

		var japan = Market.get("JP");
		assertThat(japan.minorUnits()).as("the yen has no minor unit").isEqualTo(1);
		assertThat(japan.formatMoney(500)).isEqualTo("¥ 500");
		assertThat(japan.shareOf(1_000, 3)).isEqualTo(334);

		assertThat(Market.all()).extracting(Market::country).contains("GH", "GB", "NG", "US", "JP", "KE", "ZA", "DE", "CA");
	}

	@Test
	void aMobileNumberFromAnywhereFindsItsCountry() {
		assertThat(ghana.normaliseAnyPhone("+44 7400 123456")).contains(new Market.PhoneInCountry("+447400123456", "GB"));
		assertThat(ghana.normaliseAnyPhone("024 123 4567")).as("local to Ghana").contains(new Market.PhoneInCountry("+233241234567", "GH"));
		assertThat(ghana.normaliseAnyPhone("+44 20 7946 0018")).as("a landline").isEmpty();
	}

	@org.junit.jupiter.api.Test
	void sharesRoundUpSoTheTotalIsCoveredAndMoneyReadsLikeTheWebApp() {
		var ghana = Market.get("GH");
		org.assertj.core.api.Assertions.assertThat(ghana.shareOf(25_000, 10)).isEqualTo(2_500);
		org.assertj.core.api.Assertions.assertThat(ghana.shareOf(10_000, 3)).as("3,333.3 rounds up to the next 10").isEqualTo(3_340);
		org.assertj.core.api.Assertions.assertThat(ghana.shareOf(0, 10)).isZero();
		org.assertj.core.api.Assertions.assertThat(ghana.formatMoney(2_500)).isEqualTo("GH₵ 25");
		org.assertj.core.api.Assertions.assertThat(ghana.formatMoney(1_250)).isEqualTo("GH₵ 12.50");
	}

	@org.junit.jupiter.api.Test
	void kickoffsReadLikeTheWebApp() {
		var ghana = Market.get("GH");
		var now = java.time.Instant.parse("2026-09-25T08:00:00Z"); // a Friday
		org.assertj.core.api.Assertions.assertThat(ghana.formatKickoff(java.time.Instant.parse("2026-09-25T18:00:00Z"), now)).isEqualTo("Today · 6:00 pm");
		org.assertj.core.api.Assertions.assertThat(ghana.formatKickoff(java.time.Instant.parse("2026-09-26T10:00:00Z"), now)).isEqualTo("Tomorrow · 10:00 am");
		org.assertj.core.api.Assertions.assertThat(ghana.formatKickoff(java.time.Instant.parse("2026-09-28T18:00:00Z"), now)).isEqualTo("Monday · 6:00 pm");
		org.assertj.core.api.Assertions.assertThat(ghana.formatKickoff(java.time.Instant.parse("2026-10-06T18:00:00Z"), now)).isEqualTo("Tue 6 Oct · 6:00 pm");
	}

}
