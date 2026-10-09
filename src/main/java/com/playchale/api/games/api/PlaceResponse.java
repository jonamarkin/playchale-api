package com.playchale.api.games.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.playchale.api.shared.maps.MapPin;

/**
 * A place hosts have used, as the web app's SavedPlace: what they called it, and its pin. A place
 * from Google's search whose coordinates are too old to keep comes with only its {@code placeId},
 * for the app to look up. {@code games} is how many public games were played there.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlaceResponse(String name, String area, MapPin pin, String placeId, int games) {
}
