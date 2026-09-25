package com.playchale.api.devsupport.internal.service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * IDs for the demo data, derived from the web app mock's own IDs ("u-kwame", "g-osu-sat"), so the
 * same demo game has the same ID every time the data is reset, and the web app's end-to-end tests
 * can work it out too (tests/e2e/helpers.ts does the same sum).
 */
public final class SeedIds {

	private SeedIds() {
	}

	/** A name-based (version 3) UUID of "playchale-seed/" + the mock's ID. */
	public static UUID of(String mockId) {
		return UUID.nameUUIDFromBytes(("playchale-seed/" + mockId).getBytes(StandardCharsets.UTF_8));
	}

}
