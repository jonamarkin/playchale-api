package com.playchale.api.catalog.web;

import java.util.List;

import com.playchale.api.catalog.api.Sport;
import com.playchale.api.catalog.api.SportCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CatalogController {

	/** catalog.sports */
	@GetMapping("/sports")
	List<Sport> sports() {
		return SportCatalog.all();
	}

}
