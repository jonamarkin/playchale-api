package com.playchale.api.competitions.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.playchale.api.competitions.internal.service.CorporateOperationsService;
import com.playchale.api.competitions.internal.service.CorporateReportService;
import com.playchale.api.competitions.internal.service.CorporateViews;
import com.playchale.api.shared.security.CurrentUser;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Private corporate operations. Public competition endpoints deliberately expose none of this data. */
@RestController
class CorporateOperationsController {

	private final CorporateOperationsService operations;

	private final CorporateReportService reports;

	CorporateOperationsController(CorporateOperationsService operations, CorporateReportService reports) {
		this.operations = operations;
		this.reports = reports;
	}

	record RosterMemberRequest(String displayName, String employeeReference, UUID userId) {
	}
	record ImportRosterRequest(List<CorporateOperationsService.RosterRow> rows) {
	}
	record SubmitRosterRequest(boolean attest) {
	}
	record ReviewRosterRequest(List<UUID> memberIds, String decision, String note) {
	}
	record ClaimRequest(String token) {
	}
	record LocationRequest(String name, String area, String mapUrl, UUID venueId, UUID pitchId) {
	}
	record FixtureRequest(int round, Instant startsAt, int durationMinutes, UUID locationId) {
	}
	record OfficialRequest(UUID userId) {
	}
	record FinanceRequest(long amountDue, String status, String method, String reference, Instant paidAt, String privateNote) {
	}
	record AnnouncementRequest(String audience, UUID teamId, String title, String body, boolean acknowledgement) {
	}
	record CorrectionRequest(CorporateOperationsService.SheetInput sheet, String reason) {
	}
	record RoleInviteRequest(String role, UUID teamId) {
	}

	@GetMapping("/competitions/{id}/operations")
	CorporateViews.Dashboard dashboard(CurrentUser me, @PathVariable UUID id) {
		return operations.dashboard(id, me.id());
	}

	@GetMapping("/competitions/{id}/operations/teams/{teamId}/roster")
	List<CorporateViews.RosterMember> roster(CurrentUser me, @PathVariable UUID id, @PathVariable UUID teamId) {
		return operations.roster(id, teamId, me.id());
	}

	@PostMapping("/competitions/{id}/operations/teams/{teamId}/roster")
	@ResponseStatus(HttpStatus.CREATED)
	CorporateViews.RosterMember addRosterMember(CurrentUser me, @PathVariable UUID id, @PathVariable UUID teamId,
			@RequestBody RosterMemberRequest request) {
		return operations.addRosterMember(id, teamId, request.displayName(), request.employeeReference(), request.userId(), me.id());
	}

	@PostMapping("/competitions/{id}/operations/teams/{teamId}/roster/import")
	List<CorporateViews.RosterMember> importRoster(CurrentUser me, @PathVariable UUID id, @PathVariable UUID teamId,
			@RequestBody ImportRosterRequest request) {
		return operations.importRoster(id, teamId, request.rows(), me.id());
	}

	@PostMapping("/competitions/{id}/operations/teams/{teamId}/roster/submit")
	List<CorporateViews.RosterMember> submitRoster(CurrentUser me, @PathVariable UUID id, @PathVariable UUID teamId,
			@RequestBody SubmitRosterRequest request) {
		return operations.submitRoster(id, teamId, request.attest(), me.id());
	}

	@PostMapping("/competitions/{id}/operations/teams/{teamId}/roster/review")
	List<CorporateViews.RosterMember> reviewRoster(CurrentUser me, @PathVariable UUID id, @PathVariable UUID teamId,
			@RequestBody ReviewRosterRequest request) {
		return operations.reviewRoster(id, teamId, request.memberIds(), request.decision(), request.note(), me.id());
	}

	@PostMapping("/roster-claims")
	CorporateViews.RosterMember claim(CurrentUser me, @RequestBody ClaimRequest request) {
		return operations.claim(request.token(), me.id());
	}

	@GetMapping("/competitions/{id}/operations/locations")
	List<CorporateViews.Location> locations(CurrentUser me, @PathVariable UUID id) {
		return operations.locations(id, me.id());
	}

	@PostMapping("/competitions/{id}/operations/locations")
	@ResponseStatus(HttpStatus.CREATED)
	CorporateViews.Location addLocation(CurrentUser me, @PathVariable UUID id, @RequestBody LocationRequest request) {
		return operations.addLocation(id, request.name(), request.area(), request.mapUrl(), request.venueId(), request.pitchId(), me.id());
	}

	@GetMapping("/competitions/{id}/operations/schedule")
	List<CorporateViews.Fixture> schedule(CurrentUser me, @PathVariable UUID id) {
		return operations.schedule(id, me.id());
	}

	@PostMapping("/competitions/{id}/operations/schedule/generate")
	List<CorporateViews.Fixture> generate(CurrentUser me, @PathVariable UUID id) {
		return operations.generateSchedule(id, me.id());
	}

	@GetMapping("/competitions/{id}/operations/schedule/validation")
	CorporateViews.Validation validate(CurrentUser me, @PathVariable UUID id) {
		return operations.validateSchedule(id, me.id());
	}

	@PostMapping("/competitions/{id}/operations/schedule/publish")
	CorporateViews.Validation publish(CurrentUser me, @PathVariable UUID id) {
		return operations.publishSchedule(id, me.id());
	}

	@PatchMapping("/competitions/{id}/operations/fixtures/{fixtureId}")
	CorporateViews.Fixture editFixture(CurrentUser me, @PathVariable UUID id, @PathVariable UUID fixtureId,
			@RequestBody FixtureRequest request) {
		return operations.editFixture(id, fixtureId, request.round(), request.startsAt(), request.durationMinutes(), request.locationId(), me.id());
	}

	@PutMapping("/competitions/{id}/operations/fixtures/{fixtureId}/official")
	void assignOfficial(CurrentUser me, @PathVariable UUID id, @PathVariable UUID fixtureId, @RequestBody OfficialRequest request) {
		operations.assignOfficial(id, fixtureId, request.userId(), me.id());
	}

	@GetMapping("/competitions/{id}/operations/finance")
	List<CorporateViews.Finance> finance(CurrentUser me, @PathVariable UUID id) {
		return operations.finance(id, me.id());
	}

	@PutMapping("/competitions/{id}/operations/finance/{teamId}")
	CorporateViews.Finance updateFinance(CurrentUser me, @PathVariable UUID id, @PathVariable UUID teamId,
			@RequestBody FinanceRequest request) {
		return operations.updateFinance(id, teamId, request.amountDue(), request.status(), request.method(), request.reference(),
			request.paidAt(), request.privateNote(), me.id());
	}

	@GetMapping("/competitions/{id}/operations/announcements")
	List<CorporateViews.Announcement> announcements(CurrentUser me, @PathVariable UUID id) {
		return operations.announcements(id, me.id());
	}

	@PostMapping("/competitions/{id}/operations/announcements")
	@ResponseStatus(HttpStatus.CREATED)
	CorporateViews.Announcement announce(CurrentUser me, @PathVariable UUID id, @RequestBody AnnouncementRequest request) {
		return operations.announce(id, request.audience(), request.teamId(), request.title(), request.body(), request.acknowledgement(), me.id());
	}

	@PostMapping("/announcements/{announcementId}/acknowledgement")
	void acknowledge(CurrentUser me, @PathVariable UUID announcementId) {
		operations.acknowledge(announcementId, me.id());
	}

	@GetMapping("/competitions/{id}/operations/fixtures/{fixtureId}/match-sheet")
	CorporateViews.MatchSheet matchSheet(CurrentUser me, @PathVariable UUID id, @PathVariable UUID fixtureId) {
		return operations.matchSheet(id, fixtureId, me.id());
	}

	@PutMapping("/competitions/{id}/operations/fixtures/{fixtureId}/match-sheet")
	CorporateViews.MatchSheet saveMatchSheet(CurrentUser me, @PathVariable UUID id, @PathVariable UUID fixtureId,
			@RequestBody CorporateOperationsService.SheetInput request) {
		return operations.saveMatchSheet(id, fixtureId, request, me.id());
	}

	@PostMapping("/competitions/{id}/operations/fixtures/{fixtureId}/match-sheet/submit")
	CorporateViews.MatchSheet submitMatchSheet(CurrentUser me, @PathVariable UUID id, @PathVariable UUID fixtureId,
			@RequestBody CorporateOperationsService.SheetInput request) {
		return operations.submitMatchSheet(id, fixtureId, request, me.id());
	}

	@PostMapping("/competitions/{id}/operations/fixtures/{fixtureId}/match-sheet/correct")
	CorporateViews.MatchSheet correctMatchSheet(CurrentUser me, @PathVariable UUID id, @PathVariable UUID fixtureId,
			@RequestBody CorrectionRequest request) {
		return operations.correctMatchSheet(id, fixtureId, request.sheet(), request.reason(), me.id());
	}

	@GetMapping("/competitions/{id}/operations/audit")
	List<CorporateViews.AuditEvent> audit(CurrentUser me, @PathVariable UUID id) {
		return operations.audit(id, me.id());
	}

	@PostMapping("/competitions/{id}/operations/invitations")
	CorporateViews.RoleInvitation inviteRole(CurrentUser me, @PathVariable UUID id, @RequestBody RoleInviteRequest request) {
		return operations.inviteRole(id, request.role(), request.teamId(), me.id());
	}

	@PostMapping("/competition-invitations/accept")
	UUID acceptRole(CurrentUser me, @RequestBody ClaimRequest request) {
		return operations.acceptRole(request.token(), me.id());
	}
	@GetMapping("/competitions/{id}/operations/reports/{type}.csv")
	ResponseEntity<byte[]> csv(CurrentUser me, @PathVariable UUID id, @PathVariable String type) {
		return download(reports.csv(id, type, me.id()), "text/csv", type + ".csv");
	}

	@GetMapping("/competitions/{id}/operations/reports/league.pdf")
	ResponseEntity<byte[]> pdf(CurrentUser me, @PathVariable UUID id) {
		return download(reports.pdf(id, me.id()), MediaType.APPLICATION_PDF_VALUE, "league-report.pdf");
	}

	private static ResponseEntity<byte[]> download(byte[] bytes, String contentType, String filename) {
		var headers = new HttpHeaders();
		headers.setContentType(MediaType.parseMediaType(contentType));
		headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
		return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
	}

}
