package com.playchale.api.venues.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.shared.security.CurrentUser;
import com.playchale.api.venues.internal.service.BookingResponse;
import com.playchale.api.venues.internal.service.BookingService;
import com.playchale.api.venues.internal.service.SlotResponse;
import com.playchale.api.venues.internal.service.VenueResponse;
import com.playchale.api.venues.internal.service.VenueService;
import com.playchale.api.venues.web.dto.BlockRequest;
import com.playchale.api.venues.web.dto.VenueRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
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

/** Partner venues, one endpoint per method of {@code venues} in the web app's contract. */
@RestController
class VenueController {

	private final VenueService venues;

	private final BookingService bookings;

	VenueController(VenueService venues, BookingService bookings) {
		this.venues = venues;
		this.bookings = bookings;
	}

	/** venues.search */
	@GetMapping("/venues")
	List<VenueResponse> search(@RequestParam(defaultValue = "") String query) {
		return venues.search(query);
	}

	/** venues.get: 404 when it doesn't exist, which the web app reads as null. */
	@GetMapping("/venues/{id}")
	VenueResponse get(@PathVariable UUID id) {
		return venues.get(id).orElseThrow(() -> BusinessException.notFound("That venue could not be found."));
	}

	/** venues.mine */
	@GetMapping("/me/venues")
	List<VenueResponse> mine(CurrentUser me) {
		return venues.mine(me.id());
	}

	/** venues.create */
	@PostMapping("/venues")
	@ResponseStatus(HttpStatus.CREATED)
	VenueResponse create(CurrentUser me, @Valid @RequestBody VenueRequest request) {
		return venues.create(me.id(), request.toDetails());
	}

	/** venues.update */
	@PutMapping("/venues/{id}")
	VenueResponse update(CurrentUser me, @PathVariable UUID id, @Valid @RequestBody VenueRequest request) {
		return venues.update(me.id(), id, request.toDetails());
	}

	/** venues.availability, for one local day, e.g. ?date=2026-09-26 */
	@GetMapping("/venues/{id}/availability")
	List<SlotResponse> availability(@PathVariable UUID id, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return venues.availability(id, date);
	}

	/** venues.schedule: bookings and blocks in [from, to), owner only. */
	@GetMapping("/venues/{id}/schedule")
	List<BookingResponse> schedule(CurrentUser me, @PathVariable UUID id, @RequestParam Instant from, @RequestParam Instant to) {
		return bookings.schedule(me.id(), id, from, to);
	}

	/** venues.block */
	@PostMapping("/venues/{id}/blocks")
	@ResponseStatus(HttpStatus.CREATED)
	BookingResponse block(CurrentUser me, @PathVariable UUID id, @Valid @RequestBody BlockRequest request) {
		return bookings.block(me.id(), id, request.pitchId(), request.startsAt(), request.endsAt(), request.note());
	}

	/** venues.cancelBlock */
	@DeleteMapping("/bookings/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void cancelBlock(CurrentUser me, @PathVariable UUID id) {
		bookings.cancelBlock(me.id(), id);
	}

}
