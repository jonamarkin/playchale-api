package com.playchale.api.games.internal.service;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.playchale.api.games.api.PlaceResponse;
import com.playchale.api.shared.maps.MapPin;
import com.playchale.api.shared.maps.Pin;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Places hosts have already named and pinned for public games, so the next host finds them here
 * before anyone asks Google. Each is what a host called it (never Google's name for it) and its
 * pin: a spot someone set, kept for good; or a place from Google's search, by its place ID, with
 * its coordinates while they're under 30 days old (after that the app looks them up by the ID).
 * Only public games' places: a private game's stays private.
 */
@Service
public class GamePlaces {

	private static final int MAX_WORDS = 5;

	private static final int LIMIT = 6;

	private final JdbcClient jdbc;

	GamePlaces(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Places whose name or area has every word typed, the most played first.
	 *
	 * @param country ISO 3166-1, or blank for everywhere
	 */
	@Transactional(readOnly = true)
	public List<PlaceResponse> search(String typed, String country) {
		var words = typed == null ? List.<String>of()
				: Arrays.stream(typed.toLowerCase(Locale.ROOT).strip().split("\\s+")).filter(w -> !w.isBlank()).limit(MAX_WORDS).toList();
		if (words.isEmpty() || String.join(" ", words).length() < 2) {
			return List.of();
		}
		var matching = IntStream.range(0, words.size())
			.mapToObj(i -> "AND lower(venue_name || ' ' || coalesce(venue_area, '')) LIKE :w%d ESCAPE '\\'".formatted(i))
			.collect(Collectors.joining("\n"));
		var sql = """
				WITH found AS (
				  SELECT btrim(venue_name) AS name, venue_area AS area, latitude, longitude, place_id, pin_source, pinned_at, starts_at,
				         lower(btrim(venue_name)) AS name_key,
				         coalesce(place_id, round(latitude::numeric, 3)::text || ',' || round(longitude::numeric, 3)::text) AS spot_key
				  FROM games
				  WHERE venue_kind = 'unlisted' AND visibility = 'public' AND pin_source IS NOT NULL
				    AND (latitude IS NOT NULL OR place_id IS NOT NULL)
				    AND (:country = '' OR country = :country)
				    %s
				), counted AS (
				  SELECT f.*, count(*) OVER spot AS games, max(starts_at) OVER spot AS last_played FROM found f
				  WINDOW spot AS (PARTITION BY name_key, spot_key)
				), picked AS (
				  SELECT DISTINCT ON (name_key, spot_key) * FROM counted
				  ORDER BY name_key, spot_key, (latitude IS NOT NULL) DESC, pinned_at DESC NULLS LAST, starts_at DESC
				)
				SELECT name, area, latitude, longitude, place_id, pin_source, pinned_at, games FROM picked
				ORDER BY games DESC, last_played DESC
				LIMIT %d
				""".formatted(matching, LIMIT);
		var query = jdbc.sql(sql).param("country", country == null ? "" : country.strip().toUpperCase(Locale.ROOT));
		for (int i = 0; i < words.size(); i++) {
			query = query.param("w" + i, "%" + words.get(i).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
		}
		return query.query((rs, n) -> {
			var at = rs.getObject("pinned_at", OffsetDateTime.class);
			var pin = new Pin(rs.getObject("latitude", Double.class), rs.getObject("longitude", Double.class), rs.getString("place_id"),
					rs.getString("pin_source"), at == null ? null : at.toInstant());
			MapPin shown = pin.view();
			return new PlaceResponse(rs.getString("name"), rs.getString("area"), shown, shown == null ? pin.placeId() : null, rs.getInt("games"));
		}).list();
	}

}
