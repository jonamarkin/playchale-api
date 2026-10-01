package com.playchale.api.integration.email;

import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Whether this copy of the API may send email to a list of people rather than to one person who is
 * waiting for it.
 *
 * <p>Off unless it is switched on, because the damage is one-way: a staging database holds real
 * addresses, and a weekly digest that goes out from a laptop cannot be unsent. Production sets
 * {@code PLAYCHALE_EMAIL_BULK=true} on purpose, once.
 *
 * @param bulk allow mailouts (announcements, the weekly digest) to actually leave
 */
@ConfigurationProperties("playchale.email")
public record BulkEmailProperties(@DefaultValue("false") boolean bulk) {

	/**
	 * Addresses no mailout ever goes to, whatever the setting. The demo data gives every seeded
	 * player one of these, so a mailout run against demo data is inert by construction rather than by
	 * remembering to check.
	 */
	private static final List<String> RESERVED = List.of("@example.com", "@example.org", "@example.net", "@test");

	public static boolean deliverable(String address) {
		if (address == null || address.isBlank() || !address.contains("@")) {
			return false;
		}
		var lower = address.toLowerCase(Locale.ROOT);
		return RESERVED.stream().noneMatch(lower::endsWith);
	}

}
