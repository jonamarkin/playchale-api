package com.playchale.api.competitions.web;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.competitions.internal.service.CompetitionResponse;
import com.playchale.api.competitions.internal.service.CompetitionService;
import com.playchale.api.competitions.web.dto.CompetitionRequests;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.shared.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Competitions, one endpoint per method of {@code competitions} in the web app's contract. */
@RestController
class CompetitionController {

	private final CompetitionService competitions;

	CompetitionController(CompetitionService competitions) {
		this.competitions = competitions;
	}

	/** competitions.list */
	@GetMapping("/competitions")
	List<CompetitionResponse> list(Optional<CurrentUser> me) {
		return competitions.list(me.map(CurrentUser::id).orElse(null));
	}

	/** competitions.mine */
	@GetMapping("/me/competitions")
	List<CompetitionResponse> mine(CurrentUser me) {
		return competitions.mine(me.id());
	}

	/** competitions.get: 404 when it doesn't exist, which the web app reads as null. */
	@GetMapping("/competitions/{id}")
	CompetitionResponse get(@PathVariable UUID id, Optional<CurrentUser> me) {
		return competitions.get(id, me.map(CurrentUser::id).orElse(null))
			.orElseThrow(() -> BusinessException.notFound("That league doesn’t exist any more."));
	}

	/** competitions.create */
	@PostMapping("/competitions")
	@ResponseStatus(HttpStatus.CREATED)
	CompetitionResponse create(CurrentUser me, @Valid @RequestBody CompetitionRequests.NewCompetition request) {
		return competitions.create(request.toDetails(), me.id());
	}

	/** competitions.addTeam */
	@PostMapping("/competitions/{id}/teams")
	CompetitionResponse addTeam(CurrentUser me, @PathVariable UUID id, @RequestBody CompetitionRequests.NewTeam request) {
		return competitions.addTeam(id, request.name(), request.captainId(), request.playerIds(), me.id());
	}

	/** competitions.removeTeam */
	@DeleteMapping("/competitions/{id}/teams/{teamId}")
	CompetitionResponse removeTeam(CurrentUser me, @PathVariable UUID id, @PathVariable UUID teamId) {
		return competitions.removeTeam(id, teamId, me.id());
	}

	/** competitions.generateFixtures */
	@PostMapping("/competitions/{id}/fixtures")
	CompetitionResponse generateFixtures(CurrentUser me, @PathVariable UUID id) {
		return competitions.generateFixtures(id, me.id());
	}

	/** competitions.addPlayers */
	@PostMapping("/competitions/{id}/teams/{teamId}/players")
	CompetitionResponse addPlayers(CurrentUser me, @PathVariable UUID id, @PathVariable UUID teamId,
			@RequestBody CompetitionRequests.Players request) {
		return competitions.addPlayers(id, teamId, request.userIds() == null ? List.of() : request.userIds(), me.id());
	}

	/** competitions.removePlayer */
	@DeleteMapping("/competitions/{id}/teams/{teamId}/players/{userId}")
	CompetitionResponse removePlayer(CurrentUser me, @PathVariable UUID id, @PathVariable UUID teamId, @PathVariable UUID userId) {
		return competitions.removePlayer(id, teamId, userId, me.id());
	}

	/** competitions.requestJoin */
	@PostMapping("/competitions/{id}/teams/{teamId}/requests")
	CompetitionResponse requestJoin(CurrentUser me, @PathVariable UUID id, @PathVariable UUID teamId) {
		return competitions.requestJoin(id, teamId, me.id());
	}

	/** competitions.answerRequest */
	@PostMapping("/competitions/{id}/requests/{requestId}")
	CompetitionResponse answerRequest(CurrentUser me, @PathVariable UUID id, @PathVariable UUID requestId,
			@RequestBody CompetitionRequests.Answer request) {
		return competitions.answerRequest(id, requestId, request.accept(), me.id());
	}

	/** competitions.joinWithToken */
	@PostMapping("/competitions/{id}/squad-joins")
	CompetitionResponse joinWithToken(CurrentUser me, @PathVariable UUID id, @RequestBody CompetitionRequests.SquadLink request) {
		return competitions.joinWithToken(id, request.token(), me.id());
	}

}
