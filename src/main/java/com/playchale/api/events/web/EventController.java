package com.playchale.api.events.web;

import java.util.List;
import java.util.UUID;

import com.playchale.api.events.internal.domain.GameSettings;
import com.playchale.api.events.internal.service.EventGameService;
import com.playchale.api.events.internal.service.EventService;
import com.playchale.api.events.internal.service.EventViews;
import com.playchale.api.shared.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Events: a games day in a workspace, its groups, people and games. Every change answers with the
 * whole event, as {@link EventViews.Detail}.
 */
@RestController
class EventController {

	private final EventService events;

	private final EventGameService games;

	EventController(EventService events, EventGameService games) {
		this.events = events;
		this.games = games;
	}

	record PeopleRequest(List<EventService.PersonInput> people) {
	}

	record JoinRequest(UUID groupId, List<UUID> gameIds) {
	}

	record CoordinatorsRequest(List<UUID> userIds) {
	}

	/* The event */

	@GetMapping("/organisations/{organisationId}/events")
	List<EventViews.Summary> inWorkspace(CurrentUser me, @PathVariable UUID organisationId) {
		return events.inWorkspace(organisationId, me.id());
	}

	@PostMapping("/organisations/{organisationId}/events")
	@ResponseStatus(HttpStatus.CREATED)
	EventViews.Detail create(CurrentUser me, @PathVariable UUID organisationId, @RequestBody EventService.EventInput request) {
		return events.create(organisationId, request, me.id());
	}

	@GetMapping("/me/events")
	List<EventViews.Summary> mine(CurrentUser me) {
		return events.mine(me.id());
	}

	@GetMapping("/events/{id}")
	EventViews.Detail get(CurrentUser me, @PathVariable UUID id) {
		return events.get(id, me.id());
	}

	@PatchMapping("/events/{id}")
	EventViews.Detail update(CurrentUser me, @PathVariable UUID id, @RequestBody EventService.EventInput request) {
		return events.update(id, request, me.id());
	}

	@PostMapping("/events/{id}/cancel")
	EventViews.Detail cancel(CurrentUser me, @PathVariable UUID id) {
		return events.cancel(id, me.id());
	}

	@PostMapping("/events/{id}/join-link")
	EventViews.Detail newJoinLink(CurrentUser me, @PathVariable UUID id) {
		return events.newJoinLink(id, me.id());
	}

	@PostMapping("/events/{id}/board-link")
	EventViews.Detail newBoardLink(CurrentUser me, @PathVariable UUID id) {
		return events.newBoardLink(id, me.id());
	}

	/* Groups */

	@PostMapping("/events/{id}/groups")
	EventViews.Detail addGroup(CurrentUser me, @PathVariable UUID id, @RequestBody EventService.GroupInput request) {
		return events.addGroup(id, request, me.id());
	}

	@PatchMapping("/events/{id}/groups/{groupId}")
	EventViews.Detail updateGroup(CurrentUser me, @PathVariable UUID id, @PathVariable UUID groupId,
			@RequestBody EventService.GroupInput request) {
		return events.updateGroup(id, groupId, request, me.id());
	}

	@DeleteMapping("/events/{id}/groups/{groupId}")
	EventViews.Detail removeGroup(CurrentUser me, @PathVariable UUID id, @PathVariable UUID groupId) {
		return events.removeGroup(id, groupId, me.id());
	}

	/* People */

	@PostMapping("/events/{id}/people")
	EventViews.Detail addPeople(CurrentUser me, @PathVariable UUID id, @RequestBody PeopleRequest request) {
		return events.addPeople(id, request.people(), me.id());
	}

	@PatchMapping("/events/{id}/people/{personId}")
	EventViews.Detail updatePerson(CurrentUser me, @PathVariable UUID id, @PathVariable UUID personId,
			@RequestBody EventService.PersonInput request) {
		return events.updatePerson(id, personId, request, me.id());
	}

	@DeleteMapping("/events/{id}/people/{personId}")
	EventViews.Detail removePerson(CurrentUser me, @PathVariable UUID id, @PathVariable UUID personId) {
		return events.removePerson(id, personId, me.id());
	}

	/* Joining with the link, and the signed-in player's own games */

	@GetMapping("/events/join/{code}")
	EventViews.JoinPreview preview(CurrentUser me, @PathVariable String code) {
		return events.preview(code, me.id());
	}

	@PostMapping("/events/join/{code}")
	EventViews.Detail join(CurrentUser me, @PathVariable String code, @RequestBody JoinRequest request) {
		return events.join(code, request.groupId(), request.gameIds(), me.id());
	}

	@PutMapping("/events/{id}/games/{gameId}/me")
	EventViews.Detail enter(CurrentUser me, @PathVariable UUID id, @PathVariable UUID gameId) {
		return events.enter(id, gameId, me.id());
	}

	@DeleteMapping("/events/{id}/games/{gameId}/me")
	EventViews.Detail leave(CurrentUser me, @PathVariable UUID id, @PathVariable UUID gameId) {
		return events.leave(id, gameId, me.id());
	}

	/* Games */

	@PostMapping("/events/{id}/games")
	EventViews.Detail addGame(CurrentUser me, @PathVariable UUID id, @RequestBody GameSettings.Asked request) {
		return games.add(id, request, me.id());
	}

	@PatchMapping("/events/{id}/games/{gameId}")
	EventViews.Detail updateGame(CurrentUser me, @PathVariable UUID id, @PathVariable UUID gameId,
			@RequestBody GameSettings.Asked request) {
		return games.update(id, gameId, request, me.id());
	}

	@DeleteMapping("/events/{id}/games/{gameId}")
	EventViews.Detail removeGame(CurrentUser me, @PathVariable UUID id, @PathVariable UUID gameId) {
		return games.remove(id, gameId, me.id());
	}

	@PutMapping("/events/{id}/games/{gameId}/coordinators")
	EventViews.Detail coordinators(CurrentUser me, @PathVariable UUID id, @PathVariable UUID gameId,
			@RequestBody CoordinatorsRequest request) {
		return games.coordinators(id, gameId, request.userIds(), me.id());
	}

	/* Entries */

	@PostMapping("/events/{id}/games/{gameId}/entries")
	EventViews.Detail addEntry(CurrentUser me, @PathVariable UUID id, @PathVariable UUID gameId,
			@RequestBody EventGameService.EntryInput request) {
		return games.addEntry(id, gameId, request, me.id());
	}

	@PostMapping("/events/{id}/games/{gameId}/entries/by-group")
	EventViews.Detail teamPerGroup(CurrentUser me, @PathVariable UUID id, @PathVariable UUID gameId) {
		return games.teamPerGroup(id, gameId, me.id());
	}

	@PatchMapping("/events/{id}/games/{gameId}/entries/{entryId}")
	EventViews.Detail updateEntry(CurrentUser me, @PathVariable UUID id, @PathVariable UUID gameId, @PathVariable UUID entryId,
			@RequestBody EventGameService.EntryInput request) {
		return games.updateEntry(id, gameId, entryId, request, me.id());
	}

	@DeleteMapping("/events/{id}/games/{gameId}/entries/{entryId}")
	EventViews.Detail removeEntry(CurrentUser me, @PathVariable UUID id, @PathVariable UUID gameId, @PathVariable UUID entryId) {
		return games.removeEntry(id, gameId, entryId, me.id());
	}

}
