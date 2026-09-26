package com.playchale.api.shared.maps;

import java.util.List;

import com.playchale.api.shared.error.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MapLinkTest {

	@Test
	void mapLinksAreKept() {
		for (var link : List.of("https://maps.app.goo.gl/Xy12AbCd", "https://www.google.com/maps/place/Osu/@5.55,-0.18,15z",
				"https://www.google.com.gh/maps/search/?api=1&query=osu", "https://maps.google.com/?q=5.55,-0.18",
				"https://goo.gl/maps/abc", "https://maps.apple.com/?ll=5.55,-0.18", "https://waze.com/ul?ll=5.55,-0.18")) {
			assertThat(MapLink.normalise(link)).as(link).isEqualTo(link);
		}
	}

	@Test
	void theLinkIsTakenFromWhatGoogleMapsSharesAndHttpIsUpgraded() {
		assertThat(MapLink.normalise("Osu Astro Turf\nhttps://maps.app.goo.gl/Xy12AbCd")).isEqualTo("https://maps.app.goo.gl/Xy12AbCd");
		assertThat(MapLink.normalise("http://maps.app.goo.gl/Xy12AbCd")).isEqualTo("https://maps.app.goo.gl/Xy12AbCd");
	}

	@Test
	void coordinatesBecomeAGoogleMapsLink() {
		assertThat(MapLink.normalise(" 5.5571236, -0.18184 ")).isEqualTo("https://www.google.com/maps/search/?api=1&query=5.557124,-0.18184");
		assertThatThrownBy(() -> MapLink.normalise("95, 10")).isInstanceOf(BusinessException.class);
	}

	@Test
	void blankClearsIt() {
		assertThat(MapLink.normalise("  ")).isNull();
		assertThat(MapLink.normalise(null)).isNull();
	}

	@Test
	void onlyMapServices() {
		for (var bad : List.of("https://evil.example/maps", "https://www.google.com/search?q=osu", "https://goo.gl/abc",
				"https://maps.google.com.evil.example/x", "https://user@maps.app.goo.gl/x", "https://maps.app.goo.gl:8080/x",
				"javascript:alert(1)", "Osu, near the Total station", "https://www.google.evil.example/maps")) {
			assertThatThrownBy(() -> MapLink.normalise(bad)).as(bad).isInstanceOf(BusinessException.class).hasMessage(MapLink.REFUSED);
		}
	}

}
