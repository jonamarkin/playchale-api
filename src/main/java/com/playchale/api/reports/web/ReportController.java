package com.playchale.api.reports.web;

import java.util.UUID;

import com.playchale.api.reports.internal.service.ReportService;
import com.playchale.api.shared.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Reporting something to PlayChale. An account is needed: a report has someone behind it. */
@RestController
class ReportController {

	private final ReportService reports;

	ReportController(ReportService reports) {
		this.reports = reports;
	}

	/**
	 * reports.send: {@code subjectType} is "game", "message", "user" or "venue"; {@code reason} is
	 * "spam", "abuse", "unsafe", "wrong" or "other"; {@code note} is optional.
	 */
	record ReportRequest(String subjectType, UUID subjectId, String reason, String note) {
	}

	@PostMapping("/reports")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void send(CurrentUser me, @RequestBody ReportRequest request) {
		reports.send(me.id(), request.subjectType(), request.subjectId(), request.reason(), request.note());
	}

}
