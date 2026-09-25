package com.playchale.api.devsupport.web;

import java.util.List;

import com.playchale.api.devsupport.internal.service.TestDataService;
import com.playchale.api.devsupport.internal.service.TestDataService.DemoAccount;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Demo data endpoints for a laptop and the end-to-end tests. Only present with test support on (dev profile). */
@RestController
@RequestMapping("/dev")
@ConditionalOnBooleanProperty("playchale.test-support")
class DevSupportController {

	private final TestDataService testData;

	DevSupportController(TestDataService testData) {
		this.testData = testData;
	}

	/** Back to the demo data, exactly as it starts. */
	@PostMapping("/reset")
	ResponseEntity<Void> reset() {
		testData.reset();
		return ResponseEntity.noContent().build();
	}

	/** auth.demoAccounts: seeded players to sign in as, with their demo numbers. */
	@GetMapping("/demo-accounts")
	List<DemoAccount> demoAccounts() {
		return testData.demoAccounts();
	}

}
