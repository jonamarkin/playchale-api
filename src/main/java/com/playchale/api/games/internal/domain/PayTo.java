package com.playchale.api.games.internal.domain;

import com.playchale.api.market.Market;
import com.playchale.api.shared.error.BusinessException;

/**
 * How to pay for a game while players pay outside the app: a mobile money number to send it to
 * (E.164; often not the host's own), the name on that account so players can check it before they
 * send, and anything else ("Cash to Kofi at the gate", bank details). Any of them may be missing.
 */
public record PayTo(String phone, String name, String note) {

	/**
	 * As a game in {@code market} keeps it: trimmed, the number in E.164, and null when nothing's left.
	 * A number that isn't a mobile number there is refused rather than dropped.
	 */
	public static PayTo checked(PayTo given, Market market) {
		if (given == null) {
			return null;
		}
		var name = strip(given.name());
		var note = strip(given.note());
		if (name.length() > 60) {
			throw BusinessException.invalid("Keep the name on the account under 60 characters.");
		}
		if (note.length() > 300) {
			throw BusinessException.invalid("Keep how to pay under 300 characters.");
		}
		String phone = null;
		if (!strip(given.phone()).isEmpty()) {
			phone = market.normalisePhone(given.phone())
				.orElseThrow(() -> BusinessException.invalid("Enter a valid %s mobile number to pay to, or leave it blank.".formatted(market.countryName())));
		}
		if (phone == null && name.isEmpty() && note.isEmpty()) {
			return null;
		}
		return new PayTo(phone, name.isEmpty() ? null : name, note.isEmpty() ? null : note);
	}

	private static String strip(String s) {
		return s == null ? "" : s.strip();
	}

}
