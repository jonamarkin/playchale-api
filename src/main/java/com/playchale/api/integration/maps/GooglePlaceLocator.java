package com.playchale.api.integration.maps;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * A place by its ID from Google's Places API (New): Place Details with just its ID and location,
 * which is billed as Place Details Essentials. https://developers.google.com/maps/documentation/places/web-service/place-details
 */
class GooglePlaceLocator implements PlaceLocator {

	private final RestClient http;

	private final String key;

	private final ObjectMapper json;

	GooglePlaceLocator(RestClient.Builder http, GoogleMapsProperties properties, ObjectMapper json) {
		this.http = http.baseUrl(properties.placesUrl()).build();
		this.key = properties.key().strip();
		this.json = json;
	}

	@Override
	public Optional<Located> locate(String placeId) {
		String body;
		try {
			body = http.get().uri("/v1/places/{id}", placeId).header("X-Goog-Api-Key", key).header("X-Goog-FieldMask", "id,location")
				.retrieve().body(String.class);
		}
		catch (HttpClientErrorException e) {
			// Only a 404 says the place is gone. Anything else (a bad key, a quota) is ours to fix, and
			// mustn't be taken as every place having vanished.
			if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
				return Optional.empty();
			}
			throw e;
		}
		var place = json.readTree(body);
		var location = place.path("location");
		if (!location.path("latitude").isNumber() || !location.path("longitude").isNumber()) {
			throw new IllegalStateException("Google gave no location for the place");
		}
		var id = place.path("id").asString("");
		return Optional.of(new Located(id.isBlank() ? placeId : id, location.path("latitude").asDouble(), location.path("longitude").asDouble()));
	}

}
