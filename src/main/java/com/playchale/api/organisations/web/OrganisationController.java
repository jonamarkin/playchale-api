package com.playchale.api.organisations.web;

import java.util.List;
import java.util.UUID;

import com.playchale.api.organisations.internal.service.OrganisationService;
import com.playchale.api.organisations.internal.service.OrganisationViews;
import com.playchale.api.shared.security.CurrentUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OrganisationController {

	private final OrganisationService organisations;

	OrganisationController(OrganisationService organisations) {
		this.organisations = organisations;
	}

	record CreateRequest(String name, String slug, String country, String primaryColour) {
	}

	record UpdateRequest(String name, String primaryColour, Boolean corporateEnabled) {
	}

	record AcceptRequest(String token) {
	}

	@GetMapping("/me/organisations")
	List<OrganisationViews.Organisation> mine(CurrentUser me) {
		return organisations.mine(me.id());
	}

	@PostMapping("/organisations")
	@ResponseStatus(HttpStatus.CREATED)
	OrganisationViews.Workspace create(CurrentUser me, @RequestBody CreateRequest request) {
		return organisations.create(request.name(), request.slug(), request.country(), request.primaryColour(), me.id());
	}

	@GetMapping("/organisations/{id}")
	OrganisationViews.Workspace get(CurrentUser me, @PathVariable UUID id) {
		return organisations.get(id, me.id());
	}

	@PatchMapping("/organisations/{id}")
	OrganisationViews.Workspace update(CurrentUser me, @PathVariable UUID id, @RequestBody UpdateRequest request) {
		return organisations.update(id, request.name(), request.primaryColour(), request.corporateEnabled(), me.id());
	}

	/** {"role": "admin" | "official"}; an older app that sends nothing still means an admin. */
	record InviteRequest(String role) {
	}

	@PostMapping("/organisations/{id}/invitations")
	@ResponseStatus(HttpStatus.CREATED)
	OrganisationViews.Invitation invite(CurrentUser me, @PathVariable UUID id, @RequestBody(required = false) InviteRequest request) {
		return organisations.invite(id, request == null || request.role() == null ? "admin" : request.role(), me.id());
	}

	@PostMapping("/organisation-invitations/accept")
	OrganisationViews.Workspace accept(CurrentUser me, @RequestBody AcceptRequest request) {
		return organisations.accept(request.token(), me.id());
	}

	@DeleteMapping("/organisations/{id}/members/{memberId}")
	OrganisationViews.Workspace remove(CurrentUser me, @PathVariable UUID id, @PathVariable UUID memberId) {
		return organisations.removeMember(id, memberId, me.id());
	}
	@PostMapping(value = "/organisations/{id}/logo", consumes = { "image/png", "image/jpeg", "image/webp" })
	void logo(CurrentUser me, @PathVariable UUID id, @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType,
			@RequestBody byte[] image) {
		organisations.logo(id, image, contentType, me.id());
	}

	@GetMapping("/organisations/{id}/logo")
	ResponseEntity<byte[]> logo(@PathVariable UUID id) {
		var logo = organisations.logo(id);
		return ResponseEntity.ok().contentType(MediaType.parseMediaType(logo.contentType()))
			.header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable").body(logo.bytes());
	}

}
