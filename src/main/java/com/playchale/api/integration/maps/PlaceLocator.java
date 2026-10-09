package com.playchale.api.integration.maps;

import java.util.Optional;

/**
 * Looks a place up by its ID with the map provider (Google's Places API), for its coordinates as the
 * provider has them now. Only there when a server key is set; without one, place pins are cleared
 * when they're too old to keep instead of looked up again.
 */
public interface PlaceLocator {

	/**
	 * The place now, or empty when the provider no longer has it.
	 *
	 * @throws RuntimeException when the provider couldn't be asked (try again another day)
	 */
	Optional<Located> locate(String placeId);

	/** Where the place is; its ID too, which the provider may have changed. */
	record Located(String placeId, double latitude, double longitude) {
	}

}
