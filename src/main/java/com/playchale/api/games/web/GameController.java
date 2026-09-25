package com.playchale.api.games.web;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.games.internal.service.GameFilters;
import com.playchale.api.games.internal.service.GameResponse;
import com.playchale.api.games.internal.service.GameService;
import com.playchale.api.games.internal.service.ResultService;
import com.playchale.api.games.web.dto.GameRequests.CancelRequest;
import com.playchale.api.games.web.dto.GameRequests.ClaimRequest;
import com.playchale.api.games.web.dto.GameRequests.DisputeRequest;
import com.playchale.api.games.web.dto.GameRequests.GuestRequest;
import com.playchale.api.games.web.dto.GameRequests.GuestResponse;
import com.playchale.api.games.web.dto.GameRequests.InviteRequest;
import com.playchale.api.games.web.dto.GameRequests.InviteResponse;
import com.playchale.api.games.web.dto.GameRequests.RemindRequest;
import com.playchale.api.games.web.dto.GameRequests.RemindResponse;
import com.playchale.api.games.web.dto.NewGameRequest;
import com.playchale.api.games.web.dto.ResultRequest;
import com.playchale.api.shared.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Games, one endpoint per method of {@code games} in the web app's contract. */
@RestController
class GameController {

	private final GameService games;

	private final ResultService results;

	GameController(GameService games, ResultService results) {
		this.games = games;
		this.results = results;
	}

	/** games.list */
	@GetMapping("/games")
	List<GameResponse> list(@RequestParam(required = false) String query, @RequestParam(required = false) String sport,
			@RequestParam(required = false) String when, Optional<CurrentUser> me) {
		return games.list(new GameFilters(query, sport, when), me.map(CurrentUser::id).orElse(null));
	}

	/** games.mine */
	@GetMapping("/me/games")
	List<GameResponse> mine(CurrentUser me) {
		return games.mine(me.id());
	}

	/** games.get: 404 when it doesn't exist, which the web app reads as null. */
	@GetMapping("/games/{id}")
	GameResponse get(@PathVariable UUID id, Optional<CurrentUser> me) {
		return games.get(id, me.map(CurrentUser::id).orElse(null));
	}

	/** games.create */
	@PostMapping("/games")
	@ResponseStatus(HttpStatus.CREATED)
	GameResponse create(CurrentUser me, @Valid @RequestBody NewGameRequest request) {
		return games.create(request.toDetails(), me.id());
	}

	/** games.repeat */
	@PostMapping("/games/{id}/repeat")
	@ResponseStatus(HttpStatus.CREATED)
	GameResponse repeat(CurrentUser me, @PathVariable UUID id) {
		return games.repeat(id, me.id());
	}

	/** games.join */
	@PostMapping("/games/{id}/players")
	GameResponse join(CurrentUser me, @PathVariable UUID id) {
		return games.join(id, me.id());
	}

	/** games.leave */
	@DeleteMapping("/games/{id}/players/me")
	GameResponse leave(CurrentUser me, @PathVariable UUID id) {
		return games.leave(id, me.id());
	}

	/** games.removePlayer: a player's ID, or "guest:<token>" for a held spot. */
	@DeleteMapping("/games/{id}/players/{player}")
	GameResponse removePlayer(CurrentUser me, @PathVariable UUID id, @PathVariable String player) {
		return games.removePlayer(id, player, me.id());
	}

	/** games.cancel */
	@PostMapping("/games/{id}/cancellation")
	GameResponse cancel(CurrentUser me, @PathVariable UUID id, @RequestBody(required = false) CancelRequest request) {
		return games.cancel(id, request == null ? null : request.reason(), me.id());
	}

	/** games.invite */
	@PostMapping("/games/{id}/invites")
	InviteResponse invite(CurrentUser me, @PathVariable UUID id, @RequestBody InviteRequest request) {
		return new InviteResponse(games.invite(id, request.userIds() == null ? List.of() : request.userIds(), me.id()));
	}

	/** games.addGuest */
	@PostMapping("/games/{id}/guests")
	@ResponseStatus(HttpStatus.CREATED)
	GuestResponse addGuest(CurrentUser me, @PathVariable UUID id, @RequestBody GuestRequest request) {
		var added = games.addGuest(id, request.name(), request.phone(), me.id());
		return new GuestResponse(added.game(), added.token());
	}

	/** games.claimSpot */
	@PostMapping("/games/{id}/claims")
	GameResponse claimSpot(CurrentUser me, @PathVariable UUID id, @RequestBody ClaimRequest request) {
		return games.claimSpot(id, request.token(), me.id());
	}

	/** games.remind */
	@PostMapping("/games/{id}/reminders")
	RemindResponse remind(CurrentUser me, @PathVariable UUID id, @RequestBody(required = false) RemindRequest request) {
		return new RemindResponse(games.remind(id, request == null ? null : request.userIds(), me.id()));
	}

	/** games.recordResult: recording again corrects it. */
	@PutMapping("/games/{id}/result")
	GameResponse recordResult(CurrentUser me, @PathVariable UUID id, @Valid @RequestBody ResultRequest request) {
		return results.record(id, request.toInput(), me.id());
	}

	/** games.confirmResult */
	@PostMapping("/games/{id}/result/confirmations")
	GameResponse confirmResult(CurrentUser me, @PathVariable UUID id) {
		return results.confirm(id, me.id());
	}

	/** games.disputeResult */
	@PostMapping("/games/{id}/result/disputes")
	GameResponse disputeResult(CurrentUser me, @PathVariable UUID id, @RequestBody(required = false) DisputeRequest request) {
		return results.dispute(id, request == null ? null : request.reason(), me.id());
	}

	/** games.markPaidCash */
	@PostMapping("/games/{id}/players/{player}/cash")
	GameResponse markPaidCash(CurrentUser me, @PathVariable UUID id, @PathVariable String player) {
		return games.markPaidCash(id, player, me.id());
	}

}
