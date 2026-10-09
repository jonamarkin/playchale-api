package com.playchale.api.shared.maps;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import com.playchale.api.shared.error.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Where something is on the map, as stored with it: a venue, a game somewhere that isn't a partner
 * venue, a repeating game, an event.
 *
 * <p>Whose coordinates they are decides how long they're kept. {@link #PLACE}: the place's own, from
 * Google's place search; Google lets us keep its place ID for good but the coordinates for 30 days,
 * so {@code PinRefresh} looks them up again before then (or clears them, keeping the place ID).
 * {@link #OWN}: a spot someone set themselves (the map moved under the pin, their phone's location,
 * coordinates they typed), kept for good.
 *
 * @param latitude  null for a place pin whose coordinates couldn't be looked up again in time
 * @param placeId   Google's place ID, when it came from the place search (an own pin keeps it when
 *                  the pin was moved from a place)
 * @param pinnedAt  when the coordinates were set, or last looked up for a place pin
 */
@Embeddable
public record Pin(Double latitude, Double longitude, String placeId, @Column(name = "pin_source") String source, Instant pinnedAt) {

	public static final String PLACE = "place";

	public static final String OWN = "own";

	/** How long Google allows keeping a place's coordinates. */
	public static final Duration PLACE_COORDINATES_KEPT = Duration.ofDays(30);

	private static final Pattern PLACE_ID = Pattern.compile("^[A-Za-z0-9_-]{10,300}$");

	private static final String OFF_THE_MAP = "That spot isn’t on the map. Search for the place again, or use your location.";

	/** Whether there are coordinates to show and measure from. */
	public boolean located() {
		return latitude != null && longitude != null;
	}

	/**
	 * The pin to keep for what the app sent: null for none. The same pin sent back (an edit that left
	 * it alone) keeps its {@code pinnedAt}, and so does a place's sent with its own (one found on
	 * PlayChale), so a place's coordinates aren't kept longer than allowed by saving or copying them.
	 *
	 * @throws BusinessException (invalid) for coordinates off the map, a malformed place ID or an unknown source
	 */
	public static Pin from(MapPin sent, Pin current, Instant now) {
		if (sent == null) {
			return null;
		}
		if (sent.lat() == null || sent.lng() == null || !Double.isFinite(sent.lat()) || !Double.isFinite(sent.lng())
				|| sent.lat() < -90 || sent.lat() > 90 || sent.lng() < -180 || sent.lng() > 180) {
			throw BusinessException.invalid(OFF_THE_MAP);
		}
		var placeId = sent.placeId() == null || sent.placeId().isBlank() ? null : sent.placeId().strip();
		if (placeId != null && !PLACE_ID.matcher(placeId).matches()) {
			throw BusinessException.invalid(OFF_THE_MAP);
		}
		var source = sent.source() == null ? OWN : sent.source();
		if (!PLACE.equals(source) && !OWN.equals(source) || PLACE.equals(source) && placeId == null) {
			throw BusinessException.invalid(OFF_THE_MAP);
		}
		var latitude = round(sent.lat());
		var longitude = round(sent.lng());
		if (current != null && current.located() && current.latitude().equals(latitude) && current.longitude().equals(longitude)
				&& Objects.equals(current.placeId(), placeId) && current.source().equals(source)) {
			return current;
		}
		var looked = PLACE.equals(source) && sent.pinnedAt() != null && sent.pinnedAt().isBefore(now) ? sent.pinnedAt() : now;
		return new Pin(latitude, longitude, placeId, source, looked);
	}

	/** As the web app's MapPin, or null when there are no coordinates to show. A place's says how old its coordinates are. */
	public MapPin view() {
		return located() ? new MapPin(latitude, longitude, placeId, source, PLACE.equals(source) ? pinnedAt : null) : null;
	}

	/** Kilometres between two points, along the ground. */
	public static double kmBetween(double lat1, double lng1, double lat2, double lng2) {
		var from = Math.toRadians(lat1);
		var to = Math.toRadians(lat2);
		var north = to - from;
		var east = Math.toRadians(lng2 - lng1);
		var a = Math.sin(north / 2) * Math.sin(north / 2) + Math.cos(from) * Math.cos(to) * Math.sin(east / 2) * Math.sin(east / 2);
		return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
	}

	/**
	 * The link for directions to it. A place's opens Google's own listing (by place ID, which can be
	 * kept); a spot of someone's own opens its coordinates.
	 *
	 * @param label what it's called, in case Maps can't find the place ID
	 */
	public String directions(String label) {
		if (PLACE.equals(source) && placeId != null) {
			var query = label == null || label.isBlank() ? (located() ? "%s,%s".formatted(latitude, longitude) : "place") : label.strip();
			return "https://www.google.com/maps/search/?api=1&query=%s&query_place_id=%s"
				.formatted(URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20"), placeId);
		}
		return located() ? MapLink.forCoordinates(latitude, longitude) : null;
	}

	/** Six decimals is about 10 cm: plenty for a pitch, and tidy. */
	private static double round(double degrees) {
		return Double.parseDouble(String.format(Locale.ROOT, "%.6f", degrees));
	}

}
