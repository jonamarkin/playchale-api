package com.playchale.api.usage.web;

import com.playchale.api.usage.internal.service.UsageCounts;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The web app saying a page was opened or a film played. Nobody needs to be signed in, and it's
 * the same answer whatever is sent: counted or not, there's nothing to tell the sender.
 */
@RestController
class UsageController {

	private final UsageCounts counts;

	UsageController(UsageCounts counts) {
		this.counts = counts;
	}

	record CountRequest(String kind, String name, String source) {
	}

	/** usage.count */
	@PostMapping("/usage")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void count(@RequestBody CountRequest request, @RequestHeader(value = "User-Agent", required = false) String userAgent,
			HttpServletRequest http) {
		counts.count(request.kind(), request.name(), request.source(), userAgent, http.getRemoteAddr());
	}

}
