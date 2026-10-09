package com.playchale.api.games.web;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.games.internal.service.GameFilters;
import com.playchale.api.games.api.GameResponse;
import com.playchale.api.games.api.PlaceResponse;
import com.playchale.api.games.internal.service.GameSeriesService;
import com.playchale.api.games.internal.service.GamePlaces;
import com.playchale.api.games.internal.service.GameService;
import com.playchale.api.games.internal.service.ResultService;
import com.playchale.api.games.api.GameMessageResponse;
import com.playchale.api.games.internal.service.GameTalk;
import com.playchale.api.games.web.dto.GameRequests.AttendanceRequest;
import com.playchale.api.games.web.dto.GameRequests.SayRequest;
import com.playchale.api.games.web.dto.GameRequests.CancelRequest;
import com.playchale.api.games.web.dto.GameRequests.ClaimRequest;
import com.playchale.api.games.web.dto.GameRequests.DisputeRequest;
import com.playchale.api.games.web.dto.GameRequests.GuestJoinRequest;
import com.playchale.api.games.web.dto.GameRequests.GuestRequest;
import com.playchale.api.games.web.dto.GameRequests.GuestResponse;
import com.playchale.api.games.web.dto.GameRequests.InviteAnswer;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.games.web.dto.GameRequests.InviteRequest;
import com.playchale.api.games.web.dto.GameRequests.InviteResponse;
import com.playchale.api.games.web.dto.GameRequests.TeamInviteRequest;
import com.playchale.api.games.web.dto.GameRequests.RemindRequest;
import com.playchale.api.games.web.dto.GameRequests.RemindResponse;
import com.playchale.api.games.web.dto.NewGameRequest;
import com.playchale.api.games.web.dto.ResultRequest;
import com.playchale.api.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
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

	private final GameTalk talk;

	private final GameSeriesService series;

	private final Clock clock;

	private final GamePlaces places;

	GameController(GameService games, ResultService results, GameTalk talk, GameSeriesService series, Clock clock, GamePlaces places) {
		this.games = games;
		this.results = results;
		this.talk = talk;
		this.series = series;
		this.clock = clock;
		this.places = places;
	}

	/** games.list */
	@GetMapping("/games")
	List<GameResponse> list(@RequestParam(required = false) String query, @RequestParam(required = false) String sport,
			@RequestParam(required = false) String when, @RequestParam(required = false) String country,
			@RequestParam(required = false) String zone, @RequestParam(required = false) String near, Optional<CurrentUser> me) {
		return games.list(new GameFilters(query, sport, when, country, zone, near), me.map(CurrentUser::id).orElse(null));
	}

	/** games.places: places hosts have named and pinned before, to find before asking Google. */
	@GetMapping("/places")
	List<PlaceResponse> places(@RequestParam(required = false) String query, @RequestParam(required = false) String country) {
		return places.search(query, country);
	}

	/** games.mine */
	@GetMapping("/me/games")
	List<GameResponse> mine(CurrentUser me) {
		return games.mine(me.id());
	}

	/** games.invitations: invites waiting for the player's answer. */
	@GetMapping("/me/invites")
	List<GameResponse> invitations(CurrentUser me) {
		return games.invitations(me.id());
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
		var repeats = request.repeats();
		if (repeats == null || repeats.frequency() == null) {
			return games.create(request.toDetails(clock.instant()), request.timezone(), request.homeTeamId(), request.awayTeamId(), me.id());
		}
		if (request.homeTeamId() != null || request.awayTeamId() != null) {
			throw BusinessException.invalid("A team game can’t repeat on its own. Set it up once, then use “Same again next week”.");
		}
		return series.start(request.toDetails(clock.instant()), request.timezone(), repeats.frequency(), repeats.weekOfMonth(), me.id());
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

	/** games.inviteTeam */
	@PostMapping("/games/{id}/team-invites")
	InviteResponse inviteTeam(CurrentUser me, @PathVariable UUID id, @RequestBody TeamInviteRequest request) {
		if (request.teamId() == null) {
			throw BusinessException.invalid("Pick a team to invite.");
		}
		return new InviteResponse(games.inviteTeam(id, request.teamId(), me.id()));
	}

	/** games.answerChallenge: the challenged team's captain. */
	@PostMapping("/games/{id}/challenge")
	GameResponse answerChallenge(CurrentUser me, @PathVariable UUID id, @RequestBody InviteAnswer request) {
		return games.answerChallenge(id, request.accept(), me.id());
	}

	/** games.answerInvite */
	@PostMapping("/games/{id}/invite-answers")
	GameResponse answerInvite(CurrentUser me, @PathVariable UUID id, @RequestBody InviteAnswer request) {
		return games.answerInvite(id, request.accept(), me.id());
	}

	/** games.addGuest */
	@PostMapping("/games/{id}/guests")
	@ResponseStatus(HttpStatus.CREATED)
	GuestResponse addGuest(CurrentUser me, @PathVariable UUID id, @RequestBody GuestRequest request) {
		var added = games.addGuest(id, request.name(), request.phone(), me.id());
		return new GuestResponse(added.game(), added.token(), added.spot());
	}

	/**
	 * games.joinAsGuest: taking a spot without an account. Signed-in players join as themselves, so
	 * their stats count. The token comes back once, for the browser to keep.
	 */
	@PostMapping("/games/{id}/guest-spots")
	@ResponseStatus(HttpStatus.CREATED)
	GuestResponse joinAsGuest(@PathVariable UUID id, @RequestBody GuestJoinRequest request, Optional<CurrentUser> me,
			HttpServletRequest http) {
		if (me.isPresent()) {
			throw BusinessException.conflict("You’re signed in, so join as yourself.");
		}
		var joined = games.joinAsGuest(id, request.name(), request.phone(), request.email(), http.getRemoteAddr());
		return new GuestResponse(joined.game(), joined.token(), joined.spot());
	}

	/** games.leaveAsGuest: a guest giving up their spot with the token their browser kept. */
	@DeleteMapping("/games/{id}/guest-spots/{token}")
	GameResponse leaveAsGuest(@PathVariable UUID id, @PathVariable String token) {
		return games.leaveAsGuest(id, token);
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

	/** games.messages */
	@GetMapping("/games/{id}/messages")
	List<GameMessageResponse> messages(CurrentUser me, @PathVariable UUID id) {
		return talk.list(id, me.id());
	}

	/** games.say */
	@PostMapping("/games/{id}/messages")
	List<GameMessageResponse> say(CurrentUser me, @PathVariable UUID id, @RequestBody SayRequest request) {
		return talk.say(id, request.body(), me.id());
	}

	/** games.removeMessage */
	@DeleteMapping("/games/{id}/messages/{messageId}")
	List<GameMessageResponse> removeMessage(CurrentUser me, @PathVariable UUID id, @PathVariable UUID messageId) {
		return talk.remove(id, messageId, me.id());
	}

	/** games.markAttendance */
	@PostMapping("/games/{id}/players/{player}/attendance")
	GameResponse markAttendance(CurrentUser me, @PathVariable UUID id, @PathVariable String player,
			@RequestBody AttendanceRequest request) {
		return games.markAttendance(id, player, request.showedUp(), me.id());
	}

}
