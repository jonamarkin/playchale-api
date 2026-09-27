package com.playchale.api.market;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType;

/**
 * What's true of a country rather than of the product: how phone numbers look, the currency, how
 * money is written. Any country: its name and currency come from the JDK's ISO data, its phone
 * numbers from Google's libphonenumber. Ghana keeps a few details set by hand (the GH₵ symbol).
 * Mirrors webapp/app/data/markets.ts.
 *
 * Where a game, venue or league is played, it carries its own country, currency and timezone; a
 * market's {@code timezone} is only Ghana's, and a fallback anywhere else.
 *
 * @param country        ISO 3166-1 alpha-2
 * @param dialCode       E.164 country calling code, with +
 * @param currency       ISO 4217
 * @param currencySymbol how money is written, e.g. "GH₵ 25", "£ 5"
 * @param minorUnits     minor units in one major unit (100 pesewas to the cedi, 1 for the yen)
 * @param shareStep      a player's share is rounded up to a multiple of this (minor units), so
 *                       nobody hands over awkward change
 * @param timezone       IANA: Ghana's for Ghana, UTC as a fallback elsewhere
 * @param locale         BCP 47, for dates and numbers
 * @param phoneExample   a mobile number the local way, for hints and errors
 */
public record Market(String country, String countryName, String dialCode, String currency, String currencySymbol, int minorUnits,
		int shareStep, String timezone, String locale, String phoneExample) {

	public static final String DEFAULT = "GH";

	private static final PhoneNumberUtil phones = PhoneNumberUtil.getInstance();

	/**
	 * Where the JDK's ISO data is behind: currencies that changed (Bulgaria's euro in 2026, the
	 * Caribbean guilder, Zimbabwe's ZiG), one used in practice (El Salvador's dollar), and Kosovo,
	 * which isn't in the JDK's list. The web app (Intl, country-to-currency) already has these; the
	 * catalog e2e test keeps the two in step.
	 */
	private static final Map<String, String> CURRENCY_OVERRIDES = Map.of("BG", "EUR", "CW", "XCG", "SX", "XCG", "SV", "USD", "ZW", "ZWG",
			"XK", "EUR");

	/**
	 * Currencies written without minor units in practice (CLDR), whatever ISO says: nobody writes
	 * forint or rupiah with cents. Money is stored in minor units, so the web app (Intl, which uses
	 * the same practical digits) and the API must agree.
	 */
	private static final Set<String> WHOLE_UNITS_ONLY = Set.of("AFN", "ALL", "COP", "HUF", "IDR", "IQD", "IRR", "KPW", "LAK", "LBP", "MGA",
			"MMK", "PKR", "SOS", "SYP", "YER");

	/** Every country with a phone numbering plan and a currency: the ones people can pick. */
	private static final Set<String> COUNTRIES = Stream.concat(Arrays.stream(Locale.getISOCountries()), Stream.of("XK"))
		.filter(cc -> phones.getCountryCodeForRegion(cc) != 0 && currencyCode(cc) != null)
		.collect(Collectors.toUnmodifiableSet());

	private static final Map<String, Market> MARKETS = new ConcurrentHashMap<>();

	/** A market by country code, falling back to the default for anything that isn't one. */
	public static Market get(String country) {
		var code = country == null ? "" : country.strip().toUpperCase(Locale.ROOT);
		return MARKETS.computeIfAbsent(COUNTRIES.contains(code) ? code : DEFAULT, Market::build);
	}

	/** Whether this is a country people can pick. */
	public static boolean exists(String country) {
		return country != null && COUNTRIES.contains(country.strip().toUpperCase(Locale.ROOT));
	}

	/** Every country people can pick, as markets, in code order. */
	public static List<Market> all() {
		return COUNTRIES.stream().sorted().map(Market::get).toList();
	}

	private static String currencyCode(String country) {
		if (CURRENCY_OVERRIDES.containsKey(country)) {
			return CURRENCY_OVERRIDES.get(country);
		}
		try {
			var currency = Currency.getInstance(Locale.of("", country));
			return currency == null ? null : currency.getCurrencyCode();
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** The JDK's Currency, or null for one newer than its data (XCG, ZWG). */
	private static Currency known(String code) {
		try {
			return Currency.getInstance(code);
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static Market build(String country) {
		var code = currencyCode(country);
		var currency = known(code);
		var digits = WHOLE_UNITS_ONLY.contains(code) ? 0 : currency == null ? 2 : Math.max(0, currency.getDefaultFractionDigits());
		var minorUnits = (int) Math.pow(10, digits);
		var example = phones.getExampleNumberForType(country, PhoneNumberType.MOBILE);
		var exampleText = example == null ? "" : phones.format(example, PhoneNumberFormat.NATIONAL);
		var dial = "+" + phones.getCountryCodeForRegion(country);
		var name = Locale.of("", country).getDisplayCountry(Locale.ENGLISH);
		if (DEFAULT.equals(country)) {
			// Set by hand: how people in Ghana write money and numbers.
			return new Market(country, name, dial, code, "GH₵", minorUnits, 10, "Africa/Accra", "en-GH", "024 123 4567");
		}
		var symbol = currency == null ? code : currency.getSymbol(Locale.of("en", country));
		// Shares round up to a tenth of the unit (10p, 10 kobo); a currency without cents rounds to 1.
		var step = minorUnits >= 100 ? 10 : 1;
		return new Market(country, name, dial, code, symbol, minorUnits, step, "UTC", "en-" + country, exampleText);
	}

	/**
	 * Turns what a person typed into E.164 if it's a mobile number here, or empty. The local way
	 * ("024 123 4567"), without the leading zero, or in full ("+233 24 123 4567") all work.
	 */
	public Optional<String> normalisePhone(String input) {
		if (input == null || input.isBlank()) {
			return Optional.empty();
		}
		try {
			var number = phones.parse(input, country);
			var type = phones.getNumberType(number);
			var mobile = type == PhoneNumberType.MOBILE || type == PhoneNumberType.FIXED_LINE_OR_MOBILE;
			return mobile && phones.isValidNumberForRegion(number, country) ? Optional.of(phones.format(number, PhoneNumberFormat.E164))
					: Optional.empty();
		}
		catch (NumberParseException e) {
			return Optional.empty();
		}
	}

	/**
	 * A mobile number anywhere, from E.164 or local to this market: "+44 7700 900123" signs in a
	 * phone from the UK however this market is set. Returns the number and its country.
	 */
	public Optional<PhoneInCountry> normaliseAnyPhone(String input) {
		if (input == null || input.isBlank()) {
			return Optional.empty();
		}
		try {
			var number = phones.parse(input, country);
			var region = phones.getRegionCodeForNumber(number);
			if (region == null || !exists(region)) {
				return Optional.empty();
			}
			return get(region).normalisePhone(phones.format(number, PhoneNumberFormat.E164)).map(e164 -> new PhoneInCountry(e164, region));
		}
		catch (NumberParseException e) {
			return Optional.empty();
		}
	}

	/** A mobile number in E.164, and the country it belongs to. */
	public record PhoneInCountry(String e164, String country) {
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

	/** "GH₵ 25" or "GH₵ 12.50", "£ 5", "¥ 500", as the web app writes money. */
	public String formatMoney(long minor) {
		var digits = (int) Math.round(Math.log10(minorUnits));
		var whole = minor % minorUnits == 0;
		var pattern = whole || digits == 0 ? "#,##0" : "#,##0." + "0".repeat(digits);
		var major = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.forLanguageTag(locale)));
		return currencySymbol + " " + major.format((double) minor / minorUnits);
	}

	/** "Today · 6:00 pm", "Saturday · 6:00 pm" within a week, else "Tue 3 Oct · 6:00 pm", in this market's time. */
	public String formatKickoff(Instant at, Instant now) {
		return formatKickoff(at, now, zone());
	}

	/** As above, in the given timezone: where the game is played. */
	public String formatKickoff(Instant at, Instant now, ZoneId zone) {
		var local = at.atZone(zone);
		var days = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), local.toLocalDate());
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
