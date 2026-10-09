package com.playchale.api.integration.maps;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import com.playchale.api.shared.maps.Pin;
import com.playchale.api.shared.maps.PinTable;
import com.playchale.api.shared.scheduling.ClusterLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps place pins within Google's terms: a place's coordinates from its search may be kept for 30
 * days, its ID for good. Daily, every place pin over 25 days old that's still in use (a venue, a game
 * or event to come, a repeating game) is looked up again by its ID; one Google no longer has goes.
 * Any that couldn't be looked up (no server key, Google unreachable, or a game already played) lose
 * their coordinates at 29 days, keeping the place ID for directions. On one copy of the API at a time.
 */
@Component
class PinRefresh {

	/** Looked up again from this age, so a few failed days still leave time before 30. */
	static final Duration LOOK_UP_AFTER = Duration.ofDays(25);

	/** Cleared from this age, a day inside Google's limit. */
	static final Duration CLEAR_AFTER = Pin.PLACE_COORDINATES_KEPT.minusDays(1);

	/** A day's look-ups per table: well inside Google's free monthly calls, and it catches up over days. */
	static final int PER_TABLE = 500;

	/** Failing this many in a row, it stops asking for the day: the key or Google is the trouble, not the places. */
	private static final int GIVE_UP_AFTER = 5;

	private static final Logger log = LoggerFactory.getLogger(PinRefresh.class);

	private final List<PinTable> tables;

	private final ObjectProvider<PlaceLocator> locator;

	private final ClusterLock lock;

	private final Clock clock;

	PinRefresh(List<PinTable> tables, ObjectProvider<PlaceLocator> locator, ClusterLock lock, Clock clock) {
		this.tables = tables;
		this.locator = locator;
		this.lock = lock;
		this.clock = clock;
	}

	@Scheduled(initialDelayString = "PT15M", fixedDelayString = "PT24H")
	@Transactional
	public void refresh() {
		if (!lock.tryLock("pin-refresh")) {
			return;
		}
		var now = clock.instant();
		var places = locator.getIfAvailable();
		int located = 0;
		int gone = 0;
		int cleared = 0;
		int failedInARow = 0;
		for (var table : tables) {
			for (var due : table.due(now.minus(LOOK_UP_AFTER), PER_TABLE)) {
				if (due.live() && places != null && failedInARow < GIVE_UP_AFTER) {
					try {
						var found = places.locate(due.placeId());
						failedInARow = 0;
						if (found.isPresent()) {
							table.located(due.id(), found.get().latitude(), found.get().longitude(), found.get().placeId(), now);
							located++;
						}
						else {
							table.gone(due.id());
							gone++;
						}
						continue;
					}
					catch (RuntimeException e) {
						failedInARow++;
						log.warn("Couldn't look up place {} for {} {}: {}", due.placeId(), table.table(), due.id(), e.toString());
					}
				}
				if (due.pinnedAt().isBefore(now.minus(CLEAR_AFTER))) {
					table.clear(due.id());
					cleared++;
				}
			}
		}
		if (located + gone + cleared > 0) {
			log.info("Place pins: {} looked up again, {} gone from Google, {} cleared as too old to keep", located, gone, cleared);
		}
		if (failedInARow >= GIVE_UP_AFTER) {
			log.warn("Stopped looking places up for today after {} failures in a row. Check PLAYCHALE_GOOGLE_MAPS_KEY and that the Places API (New) is on for it.",
					failedInARow);
		}
	}

}
