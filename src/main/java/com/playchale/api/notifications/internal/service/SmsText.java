package com.playchale.api.notifications.internal.service;

import java.text.Normalizer;

/**
 * Texts that fit one SMS: 160 plain characters (a curly quote or an accent would halve how many fit,
 * and double what it costs), a game's name and place shortened between words to make room.
 */
final class SmsText {

	/** One SMS. */
	static final int LENGTH = 160;

	private SmsText() {
	}

	/**
	 * {@code format} with the game's name and place in it, in that order (two {@code %s}), everything
	 * else already plain. The place gives way first, down to a few words; then the name.
	 */
	static String fit(String format, String title, String place) {
		var room = LENGTH - (format.length() - 4);
		var where = plain(place);
		var name = plain(title);
		if (name.length() + where.length() > room) {
			where = shorten(where, Math.max(24, room - name.length()));
		}
		if (name.length() + where.length() > room) {
			name = shorten(name, Math.max(1, room - where.length()));
		}
		return format.formatted(name, where);
	}

	/** A link without its scheme: "playchale.com/games/…". */
	static String link(String url) {
		return plain(url.replaceFirst("^https?://", ""));
	}

	/** At most {@code max} characters, cut at the last space that fits (mid-word only for one long word). */
	static String shorten(String s, int max) {
		if (s.length() <= max) {
			return s;
		}
		var cut = s.substring(0, max);
		var space = cut.lastIndexOf(' ');
		return (space > 0 ? cut.substring(0, space) : cut).replaceAll("[\\s,.;:\\-]+$", "");
	}

	/**
	 * Only characters every phone sends as plain text: accents taken off, curly quotes and dashes made
	 * straight, anything else left out.
	 */
	static String plain(String s) {
		var straight = (s == null ? "" : s).replace('‘', '\'').replace('’', '\'').replace('“', '"').replace('”', '"').replace('–', '-')
			.replace('—', '-').replace('·', '-').replace('…', '.');
		var bare = Normalizer.normalize(straight, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
		return bare.replaceAll("[^A-Za-z0-9 .,:;!?'\"()&@#%*+=/_\\-]", "").replaceAll("\\s+", " ").strip();
	}

}
