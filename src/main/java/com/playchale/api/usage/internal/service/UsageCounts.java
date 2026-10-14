package com.playchale.api.usage.internal.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.playchale.api.shared.security.RateLimiter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counts pages opened and films played on the web app, by day, so the admin desk can say what's
 * used and whether the pages sent to a church or a company were opened. Only numbers are kept:
 * never who, never their address, never a cookie (see V49).
 *
 * <p>Anything that doesn't look right is quietly not counted rather than refused: a count is never
 * worth an error on someone's screen, and a junk request learns nothing from the answer. Robots
 * (search engines, link previews, headless browsers) aren't counted, and one connection counts at
 * most {@link #PER_CONNECTION_PER_HOUR} times an hour, so a script can't make a page look popular.
 */
@Service
public class UsageCounts {

	public static final int PER_CONNECTION_PER_HOUR = 600;

	private static final Set<String> KINDS = Set.of("page", "film-start", "film-end");

	/** A route ("/games/:id") or a film's id ("gamesday"): never an address with someone's data in it. */
	private static final Pattern NAME = Pattern.compile("[a-z0-9/:_.-]{1,80}");

	/** A tag put on a link ("hillview", "church-email"), or a site's name once tidied. */
	private static final Pattern SOURCE = Pattern.compile("[a-z0-9._-]{1,60}");

	private static final Pattern ROBOT = Pattern.compile("(?i)bot|crawl|spider|slurp|headless|lighthouse|preview");

	/** Second-level names that sit under a country's own (bbc.co.uk, myjoyonline.com.gh). */
	private static final Set<String> SECOND_LEVEL = Set.of("co", "com", "org", "net", "gov", "edu", "ac");

	private final JdbcClient jdbc;

	private final RateLimiter limiter;

	private final Clock clock;

	UsageCounts(JdbcClient jdbc, RateLimiter limiter, Clock clock) {
		this.jdbc = jdbc;
		this.limiter = limiter;
		this.clock = clock;
	}

	/**
	 * Counts one: a page opened, a film started or watched to the end.
	 *
	 * @param source where the visit came from, on its first page only: another site's address, a tag
	 *               from the link, or "direct"; blank for a page opened from inside the app
	 */
	@Transactional
	public void count(String kind, String name, String source, String userAgent, String connection) {
		var what = kind == null ? "" : kind.strip();
		var called = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
		if (!KINDS.contains(what) || !NAME.matcher(called).matches()) {
			return;
		}
		if (userAgent == null || userAgent.isBlank() || ROBOT.matcher(userAgent).find()) {
			return;
		}
		var from = what.equals("page") ? source(source) : "";
		if (from == null || !limiter.tryAcquire("usage:" + connection, Duration.ofHours(1), PER_CONNECTION_PER_HOUR)) {
			return;
		}
		jdbc.sql("""
				INSERT INTO usage_counts (day, kind, name, source, n) VALUES (:day, :kind, :name, :source, 1)
				ON CONFLICT (day, kind, name, source) DO UPDATE SET n = usage_counts.n + 1
				""")
			.param("day", LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC))
			.param("kind", what)
			.param("name", called)
			.param("source", from)
			.update();
	}

	/**
	 * Where a visit came from, tidied so the same place always reads the same: "l.facebook.com" and
	 * "m.facebook.com" are both facebook.com, and a tag typed as "Hillview Chapel" is
	 * "hillview-chapel". Null when nothing's left of it (not counted).
	 */
	static String source(String raw) {
		if (raw == null || raw.isBlank()) {
			return "";
		}
		var tidy = raw.strip().toLowerCase(Locale.ROOT).replaceFirst("^https?://", "").replaceFirst("[/?#:].*$", "");
		if (tidy.contains(".")) {
			var labels = tidy.split("\\.");
			int keep = labels.length >= 3 && labels[labels.length - 1].length() == 2 && SECOND_LEVEL.contains(labels[labels.length - 2]) ? 3 : 2;
			tidy = String.join(".", Arrays.copyOfRange(labels, Math.max(0, labels.length - keep), labels.length));
		}
		tidy = tidy.replaceAll("[^a-z0-9._-]+", "-").replaceAll("^[-.]+|[-.]+$", "");
		tidy = tidy.substring(0, Math.min(tidy.length(), 60));
		return SOURCE.matcher(tidy).matches() ? tidy : null;
	}

}
