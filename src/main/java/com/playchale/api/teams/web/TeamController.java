package com.playchale.api.teams.web;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.shared.security.CurrentUser;
import com.playchale.api.teams.internal.service.TeamResponse;
import com.playchale.api.teams.internal.service.TeamService;
import com.playchale.api.teams.web.dto.TeamRequests;
import org.springframework.http.HttpStatus;
import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Teams, one endpoint per method of {@code teams} in the web app's contract. */
@RestController
class TeamController {

	private final TeamService teams;

	TeamController(TeamService teams) {
		this.teams = teams;
	}

	/** teams.create */
	@PostMapping("/teams")
	@ResponseStatus(HttpStatus.CREATED)
	TeamResponse create(CurrentUser me, @RequestBody TeamRequests.NewTeam request) {
		return teams.create(request.name(), request.tint(), request.memberIds(), me.id());
	}

	/** teams.get: 404 when it doesn't exist, which the web app reads as null. */
	@GetMapping("/teams/{id}")
	TeamResponse get(@PathVariable UUID id, Optional<CurrentUser> me) {
		return teams.get(id, me.map(CurrentUser::id).orElse(null))
			.orElseThrow(() -> BusinessException.notFound("That team doesn’t exist any more."));
	}

	/** teams.search */
	@GetMapping("/teams")
	List<TeamResponse.Found> search(CurrentUser me, @RequestParam(defaultValue = "") String query) {
		return teams.search(query, me.id());
	}

	/** teams.mine */
	@GetMapping("/me/teams")
	List<TeamResponse> mine(CurrentUser me) {
		return teams.mine(me.id());
	}

	/** teams.update */
	@PatchMapping("/teams/{id}")
	TeamResponse update(CurrentUser me, @PathVariable UUID id, @RequestBody TeamRequests.Changes request) {
		return teams.update(id, request.name(), request.tint(), request.captainId(), me.id());
	}

	/** teams.addMembers */
	/** teams.setLogo: the crest, already resized by the browser. Captain only. */
	@PutMapping(value = "/teams/{id}/logo", consumes = MediaType.ALL_VALUE)
	TeamResponse setLogo(CurrentUser me, @PathVariable UUID id, @RequestBody byte[] image,
			@RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType) {
		return teams.setLogo(id, image, contentType, me.id());
	}

	/** teams.removeLogo */
	@DeleteMapping("/teams/{id}/logo")
	TeamResponse removeLogo(CurrentUser me, @PathVariable UUID id) {
		return teams.removeLogo(id, me.id());
	}

	/**
	 * The crest image. Public, like the team's name: its URL carries the version, so it's cached for
	 * good and a new crest is a new URL.
	 */
	@GetMapping("/teams/{id}/logo")
	ResponseEntity<byte[]> logo(@PathVariable UUID id) {
		return teams.logo(id)
			.map(crest -> ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(crest.contentType()))
				.cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
				.eTag(String.valueOf(crest.version().toEpochMilli()))
				.body(crest.image()))
			.orElseGet(() -> ResponseEntity.notFound().build());
	}

	@PostMapping("/teams/{id}/members")
	TeamResponse addMembers(CurrentUser me, @PathVariable UUID id, @RequestBody TeamRequests.Members request) {
		return teams.addMembers(id, request.userIds() == null ? List.of() : request.userIds(), me.id());
	}

	/** teams.removeMember: the captain takes someone out, or someone leaves (their own id). */
	@DeleteMapping("/teams/{id}/members/{userId}")
	TeamResponse removeMember(CurrentUser me, @PathVariable UUID id, @PathVariable UUID userId) {
		return teams.removeMember(id, userId, me.id());
	}

	/** teams.requestJoin */
	@PostMapping("/teams/{id}/requests")
	TeamResponse requestJoin(CurrentUser me, @PathVariable UUID id) {
		return teams.requestJoin(id, me.id());
	}

	/** teams.answerRequest */
	@PostMapping("/teams/{id}/requests/{requestId}")
	TeamResponse answerRequest(CurrentUser me, @PathVariable UUID id, @PathVariable UUID requestId, @RequestBody TeamRequests.Answer request) {
		return teams.answerRequest(id, requestId, request.accept(), me.id());
	}

	/** teams.joinWithToken: from the team's link. */
	@PostMapping("/teams/{id}/joins")
	TeamResponse join(CurrentUser me, @PathVariable UUID id, @RequestBody TeamRequests.Link request) {
		return teams.joinWithToken(id, request.token(), me.id());
	}

	/** teams.remove */
	@DeleteMapping("/teams/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(CurrentUser me, @PathVariable UUID id) {
		teams.delete(id, me.id());
	}

}
