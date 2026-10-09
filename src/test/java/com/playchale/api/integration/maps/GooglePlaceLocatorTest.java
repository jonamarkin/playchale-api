package com.playchale.api.integration.maps;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The Google adapter against a stand-in for the Places API: what it asks for, and what each answer becomes. */
class GooglePlaceLocatorTest {

	private static final GoogleMapsProperties GOOGLE = new GoogleMapsProperties("not-a-real-key", "https://places.googleapis.com");

	private static final String LABONE = "ChIJ8a8Bqb2a3w8R5oQkY6X3n2k";

	@Test
	void asksForOnlyTheIdAndLocationSoItsBilledAsEssentials() {
		var builder = RestClient.builder();
		var google = MockRestServiceServer.bindTo(builder).build();
		var locator = new GooglePlaceLocator(builder, GOOGLE, JsonMapper.builder().build());
		google.expect(requestTo("https://places.googleapis.com/v1/places/" + LABONE))
			.andExpect(method(HttpMethod.GET))
			.andExpect(header("X-Goog-Api-Key", "not-a-real-key"))
			.andExpect(header("X-Goog-FieldMask", "id,location"))
			.andRespond(withSuccess("""
					{"id":"ChIJnewIdAfterAMove01","location":{"latitude":5.5641,"longitude":-0.1692}}
					""", MediaType.APPLICATION_JSON));

		var found = locator.locate(LABONE);

		assertThat(found).contains(new PlaceLocator.Located("ChIJnewIdAfterAMove01", 5.5641, -0.1692));
		google.verify();
	}

	@Test
	void onlyNotFoundMeansThePlaceIsGone() {
		var builder = RestClient.builder();
		var google = MockRestServiceServer.bindTo(builder).build();
		var locator = new GooglePlaceLocator(builder, GOOGLE, JsonMapper.builder().build());
		google.expect(requestTo("https://places.googleapis.com/v1/places/" + LABONE)).andRespond(withStatus(HttpStatus.NOT_FOUND)
			.contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\"}}"));
		google.expect(requestTo("https://places.googleapis.com/v1/places/" + LABONE)).andRespond(withStatus(HttpStatus.FORBIDDEN)
			.contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"code\":403,\"status\":\"PERMISSION_DENIED\"}}"));

		assertThat(locator.locate(LABONE)).isEmpty();
		// A bad key isn't every place vanishing.
		assertThatThrownBy(() -> locator.locate(LABONE)).isInstanceOf(HttpClientErrorException.class);
		google.verify();
	}

}
