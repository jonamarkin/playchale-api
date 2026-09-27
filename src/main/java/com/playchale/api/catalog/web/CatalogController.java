package com.playchale.api.catalog.web;

import java.util.List;

import com.playchale.api.catalog.api.Sport;
import com.playchale.api.catalog.api.SportCatalog;
import com.playchale.api.market.Market;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CatalogController {

	/** catalog.sports */
	@GetMapping("/sports")
	List<Sport> sports() {
		return SportCatalog.all();
	}

	/** A country as the web app's own list builds it, so the two can be checked against each other. */
	record Country(String code, String name, String currency, int minorUnits, String dialCode) {
	}

	/** Every country people can pick, with its currency and dial code. */
	@GetMapping("/countries")
	List<Country> countries() {
		return Market.all().stream().map(m -> new Country(m.country(), m.countryName(), m.currency(), m.minorUnits(), m.dialCode())).toList();
	}

}
