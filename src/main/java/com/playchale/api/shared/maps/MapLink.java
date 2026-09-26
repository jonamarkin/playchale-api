package com.playchale.api.shared.maps;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

import com.playchale.api.shared.error.BusinessException;

/**
 * Where to find a place on a map: a link people tap for directions to a venue or game.
 *
 * <p>Accepts what people actually have to hand: a link shared from Google Maps (often with the
 * place's name in front of it, as the Share button copies it), an Apple Maps or Waze link, or
 * coordinates ("5.5571, -0.1818", from the phone's own location). Only those map services are
 * allowed, so a "directions" button can never lead somewhere else. Mirrored by the web app's
 * {@code utils/maps.ts}; keep the two in step.
 */
public final class MapLink {

	public static final String REFUSED = "Paste a link from Google Maps, Apple Maps or Waze, or leave it blank.";

	private static final int MAX_LENGTH = 500;

	private static final Pattern COORDINATES = Pattern.compile("^\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*,\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$");

	/** The first link in whatever was pasted: Google Maps' Share puts the place's name before it. */
	private static final Pattern FIRST_LINK = Pattern.compile("https?://\\S+");

	/** google.com, google.com.gh and the like. */
	private static final Pattern GOOGLE = Pattern.compile("^(www\\.)?google\\.[a-z]{2,3}(\\.[a-z]{2})?$");

	private MapLink() {
	}

	/**
	 * The link to store, or null when the field was left blank (which clears it).
	 *
	 * @throws BusinessException (invalid) when it isn't a map link or coordinates
	 */
	public static String normalise(String typed) {
		if (typed == null || typed.isBlank()) {
			return null;
		}
		var coordinates = COORDINATES.matcher(typed);
		if (coordinates.matches()) {
			return forCoordinates(Double.parseDouble(coordinates.group(1)), Double.parseDouble(coordinates.group(2)));
		}
		var link = FIRST_LINK.matcher(typed);
		if (!link.find()) {
			throw BusinessException.invalid(REFUSED);
		}
		var text = link.group().replaceFirst("^http://", "https://");
		if (text.length() > MAX_LENGTH) {
			throw BusinessException.invalid(REFUSED);
		}
		URI uri;
		try {
			uri = new URI(text);
		}
		catch (URISyntaxException e) {
			throw BusinessException.invalid(REFUSED);
		}
		if (uri.getHost() == null || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443) || !isMapService(uri)) {
			throw BusinessException.invalid(REFUSED);
		}
		return text;
	}

	/** A Google Maps link to a spot, which opens the Maps app on a phone. */
	public static String forCoordinates(double latitude, double longitude) {
		if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
			throw BusinessException.invalid("Those coordinates aren’t on the map. Try the location button, or paste a Google Maps link.");
		}
		return "https://www.google.com/maps/search/?api=1&query=%s,%s".formatted(round(latitude), round(longitude));
	}

	private static String round(double degrees) {
		// Six decimals is about 10 cm: plenty for a pitch, and tidy.
		return String.format(Locale.ROOT, "%.6f", degrees).replaceFirst("0+$", "").replaceFirst("\\.$", "");
	}

	private static boolean isMapService(URI uri) {
		var host = uri.getHost().toLowerCase(Locale.ROOT);
		var path = uri.getRawPath() == null ? "" : uri.getRawPath();
		return switch (host) {
			case "maps.app.goo.gl", "maps.google.com", "maps.apple.com", "waze.com", "www.waze.com", "ul.waze.com" -> true;
			case "goo.gl" -> path.startsWith("/maps");
			default -> GOOGLE.matcher(host).matches() && path.startsWith("/maps");
		};
	}

}
