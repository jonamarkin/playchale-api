package com.playchale.api.shared.maps;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A module's table of {@link Pin}s, as the daily look-up of place coordinates sees it. Each module
 * that stores pins declares one for its own table (a bean extending this), so the job never reaches
 * into a table no module offered it.
 */
public abstract class PinTable {

	/** A place pin due to be looked up again. {@code live}: still worth the look-up (a game to come, not a past one). */
	public record Due(UUID id, String placeId, Instant pinnedAt, boolean live) {
	}

	private final JdbcClient jdbc;

	private final String table;

	private final String live;

	/**
	 * @param table the table, with the V41 pin columns
	 * @param live  SQL for the rows still in use, whose place pins are looked up again; the others'
	 *              coordinates are just cleared after 30 days
	 */
	protected PinTable(JdbcClient jdbc, String table, String live) {
		this.jdbc = jdbc;
		this.table = table;
		this.live = live;
	}

	public String table() {
		return table;
	}

	/** Place pins last looked up before {@code before}, oldest first, apart from cleared ones nobody needs any more. */
	public List<Due> due(Instant before, int limit) {
		return jdbc.sql("""
				SELECT id, place_id, pinned_at, (%2$s) AS live FROM %1$s
				WHERE pin_source = 'place' AND pinned_at < :before AND (latitude IS NOT NULL OR (%2$s))
				ORDER BY pinned_at LIMIT :limit
				""".formatted(table, live))
			.param("before", before.atOffset(ZoneOffset.UTC)).param("limit", limit)
			.query((rs, n) -> new Due(rs.getObject("id", UUID.class), rs.getString("place_id"),
					rs.getObject("pinned_at", OffsetDateTime.class).toInstant(), rs.getBoolean("live")))
			.list();
	}

	/** Looked up again: the place's coordinates as Google has them now (its ID may have changed too). */
	public void located(UUID id, double latitude, double longitude, String placeId, Instant at) {
		jdbc.sql("UPDATE %s SET latitude = :lat, longitude = :lng, place_id = :placeId, pinned_at = :at WHERE id = :id AND pin_source = 'place'"
			.formatted(table))
			.param("lat", latitude).param("lng", longitude).param("placeId", placeId).param("at", at.atOffset(ZoneOffset.UTC)).param("id", id)
			.update();
	}

	/** Google no longer has the place: the pin goes. */
	public void gone(UUID id) {
		jdbc.sql("""
				UPDATE %s SET latitude = NULL, longitude = NULL, place_id = NULL, pin_source = NULL, pinned_at = NULL
				WHERE id = :id AND pin_source = 'place'
				""".formatted(table)).param("id", id).update();
	}

	/** Too old to keep and not looked up again: the coordinates go, the place ID stays. */
	public void clear(UUID id) {
		jdbc.sql("UPDATE %s SET latitude = NULL, longitude = NULL WHERE id = :id AND pin_source = 'place'".formatted(table))
			.param("id", id).update();
	}

}
