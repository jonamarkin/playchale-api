package com.playchale.api.market;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
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
 * @param currencySymbol how money is written, e.g. "GH₵ 25"
 * @param minorUnits     minor units in one major unit (100 pesewas to the cedi)
 * @param shareStep      a player's share is rounded up to a multiple of this (minor units), so
 *                       nobody hands over awkward change
 * @param timezone       IANA
 * @param locale         BCP 47, for dates and numbers
 */
public record Market(String country, String countryName, String dialCode, Pattern mobilePattern, String currency,
		String currencySymbol, int minorUnits, int shareStep, String timezone, String locale) {

	public static final String DEFAULT = "GH";

	private static final Map<String, Market> MARKETS = Map.of("GH",
			new Market("GH", "Ghana", "+233", Pattern.compile("^[2-5]\\d{8}$"), "GHS", "GH₵", 100, 10, "Africa/Accra", "en-GH"));

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

	public ZoneId zone() {
		return ZoneId.of(timezone);
	}

	/**
	 * Each player's share of a cost, rounded up to {@link #shareStep} so the total is always covered.
	 * Same sum as the web app's shareOf.
	 */
	public long shareOf(long total, int players) {
		if (total <= 0 || players <= 0) {
			return 0;
		}
		long perPlayer = (total + players - 1) / players;
		return (perPlayer + shareStep - 1) / shareStep * shareStep;
	}

	/** "GH₵ 25" or "GH₵ 12.50", as the web app writes money. */
	public String formatMoney(long minor) {
		var major = new DecimalFormat(minor % minorUnits == 0 ? "#,##0" : "#,##0.00", DecimalFormatSymbols.getInstance(Locale.forLanguageTag(locale)));
		return currencySymbol + " " + major.format((double) minor / minorUnits);
	}

	/** "Today · 6:00 pm", "Saturday · 6:00 pm" within a week, else "Tue 3 Oct · 6:00 pm", in local time. */
	public String formatKickoff(Instant at, Instant now) {
		var local = at.atZone(zone());
		var days = ChronoUnit.DAYS.between(now.atZone(zone()).toLocalDate(), local.toLocalDate());
		var language = Locale.forLanguageTag(locale);
		String day;
		if (days == 0) {
			day = "Today";
		}
		else if (days == 1) {
			day = "Tomorrow";
		}
		else if (days == -1) {
			day = "Yesterday";
		}
		else if (days > 1 && days < 7) {
			day = DateTimeFormatter.ofPattern("EEEE", language).format(local);
		}
		else {
			day = DateTimeFormatter.ofPattern("EEE d MMM", language).format(local);
		}
		var time = DateTimeFormatter.ofPattern("h:mm a", language).format(local).toLowerCase(language);
		return day + " · " + time;
	}

}
