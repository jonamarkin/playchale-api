package com.playchale.api.devsupport.web;

import com.playchale.api.devsupport.internal.service.TestDataService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints for the web app's end-to-end tests. Only present with test support on (dev profile). */
@RestController
@RequestMapping("/dev")
@ConditionalOnBooleanProperty("playchale.test-support")
class DevSupportController {

	private final TestDataService testData;

	DevSupportController(TestDataService testData) {
		this.testData = testData;
	}

	@PostMapping("/reset")
	ResponseEntity<Void> reset() {
		testData.reset();
		return ResponseEntity.noContent().build();
	}

}
