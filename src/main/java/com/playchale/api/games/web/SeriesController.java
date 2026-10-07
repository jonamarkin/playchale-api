package com.playchale.api.games.web;

import java.util.List;
import java.util.UUID;

import com.playchale.api.games.api.GameResponse;
import com.playchale.api.games.api.SeriesResponse;
import com.playchale.api.games.internal.service.GameSeriesService;
import com.playchale.api.games.web.dto.GameRequests.RepeatsRequest;
import com.playchale.api.games.web.dto.GameRequests.SeriesChangeRequest;
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

/** Repeating games, one endpoint per method of {@code series} in the web app's contract. */
@RestController
class SeriesController {

	private final GameSeriesService series;

	SeriesController(GameSeriesService series) {
		this.series = series;
	}

	/** series.startFrom: the host's game, repeating from now on. Returns the game to show: the next one, if it opened straight away. */
	@PostMapping("/games/{id}/series")
	@ResponseStatus(HttpStatus.CREATED)
	GameResponse startFrom(CurrentUser me, @PathVariable UUID id, @RequestBody RepeatsRequest repeats) {
		return series.startFrom(id, repeats.frequency(), repeats.weekOfMonth(), me.id());
	}

	/** series.mine */
	@GetMapping("/me/series")
	List<SeriesResponse> mine(CurrentUser me) {
		return series.mine(me.id());
	}

	/** series.change */
	@PatchMapping("/series/{id}")
	SeriesResponse change(CurrentUser me, @PathVariable UUID id, @RequestBody SeriesChangeRequest change) {
		return series.change(id, change.toChange(), me.id());
	}

	/** series.stop */
	@PostMapping("/series/{id}/stop")
	SeriesResponse stop(CurrentUser me, @PathVariable UUID id) {
		return series.stop(id, me.id());
	}

	/** series.restart */
	@PostMapping("/series/{id}/restart")
	SeriesResponse restart(CurrentUser me, @PathVariable UUID id) {
		return series.restart(id, me.id());
	}

	/** series.optOut: stop inviting me. */
	@PutMapping("/series/{id}/optout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void optOut(CurrentUser me, @PathVariable UUID id) {
		series.optOut(id, true, me.id());
	}

	/** series.optIn: invite me again. */
	@DeleteMapping("/series/{id}/optout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void optIn(CurrentUser me, @PathVariable UUID id) {
		series.optOut(id, false, me.id());
	}

}
