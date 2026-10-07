package com.playchale.api.games.internal.service;

import java.time.Clock;

import com.playchale.api.games.internal.repository.GameSeriesRepository;
import com.playchale.api.shared.error.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Opens repeating games' next games. Every couple of minutes it finds the series whose last game has
 * ended and opens each one's next, in its own transaction, so one series' trouble never holds up the
 * rest.
 *
 * <p>Safe with several copies of the API running: each series is locked while its next game opens,
 * and opening re-checks it's still due once it has the lock, so a second copy finds nothing to do.
 * (And the database refuses a second game on the same date of the same series.)
 */
@Component
class SeriesOpener {

	private static final int BATCH = 50;

	private static final Logger log = LoggerFactory.getLogger(SeriesOpener.class);

	private final GameSeriesRepository series;

	private final GameSeriesService service;

	private final Clock clock;

	SeriesOpener(GameSeriesRepository series, GameSeriesService service, Clock clock) {
		this.series = series;
		this.service = service;
		this.clock = clock;
	}

	@Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT2M")
	public void openDue() {
		for (var id : series.due(clock.instant(), Limit.of(BATCH))) {
			try {
				service.openIfDue(id);
			}
			catch (RuntimeException e) {
				log.warn("Couldn't open the next game of series {}; skipping that date", id, e);
				try {
					service.skipAfterFailure(id, e instanceof BusinessException known ? known.getMessage()
							: "Something went wrong setting it up, so that date was skipped.");
				}
				catch (RuntimeException again) {
					log.warn("Couldn't skip the date either for series {}; will try again", id, again);
				}
			}
		}
	}

}
