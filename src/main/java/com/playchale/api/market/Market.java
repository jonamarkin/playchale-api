package com.playchale.api.market;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What's true of a country rather than of the product: how phone numbers look, the currency, the
 * timezone. Mirrors webapp/app/data/markets.ts. Ghana is the only market today; another country is
 * a new entry here, not a change anywhere else.
 *
 * @param country        ISO 3166-1 alpha-2
 * @param dialCode       E.164 country calling code, with +
 * @param mobilePattern  valid national numbers, without the leading 0
 * @param currency       ISO 4217
 * @param timezone       IANA
 */
public record Market(String country, String countryName, String dialCode, Pattern mobilePattern, String currency,
		String timezone) {

	public static final String DEFAULT = "GH";

	private static final Map<String, Market> MARKETS = Map.of("GH",
			new Market("GH", "Ghana", "+233", Pattern.compile("^[2-5]\\d{8}$"), "GHS", "Africa/Accra"));

	/** A market by country code, falling back to the default. */
	public static Market get(String country) {
		return MARKETS.getOrDefault(country == null ? "" : country.toUpperCase(), MARKETS.get(DEFAULT));
	}

	/**
	 * Turns what a person typed into E.164 for this market, or empty if it isn't a mobile number here.
	 * "024 123 4567", "241234567" and "+233241234567" all give "+233241234567".
	 */
	public Optional<String> normalisePhone(String input) {
		if (input == null) {
			return Optional.empty();
		}
		var national = input.replaceAll("\\D", "");
		var dialDigits = dialCode.substring(1);
		if (national.startsWith(dialDigits)) {
			national = national.substring(dialDigits.length());
		}
		if (national.startsWith("0")) {
			national = national.substring(1);
		}
		return mobilePattern.matcher(national).matches() ? Optional.of(dialCode + national) : Optional.empty();
	}

}
