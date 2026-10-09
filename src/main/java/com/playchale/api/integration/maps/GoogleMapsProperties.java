package com.playchale.api.integration.maps;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Google Maps Platform, on the API's side. The web app has its own browser key for the search and the
 * maps people see (NUXT_PUBLIC_GOOGLE_MAPS_KEY); this one is for looking places up again so their
 * coordinates are never kept longer than Google allows.
 *
 * @param key       a server key with only the Places API (New) on it, restricted to the server's IP
 *                  address: PLAYCHALE_GOOGLE_MAPS_KEY. Empty: place coordinates are cleared after 30
 *                  days instead of looked up again
 * @param placesUrl Google's Places API, only changed in tests
 */
@ConfigurationProperties("playchale.google.maps")
public record GoogleMapsProperties(String key, @DefaultValue("https://places.googleapis.com") String placesUrl) {

}
