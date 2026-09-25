package com.playchale.api.venues.internal.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.market.Market;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.venues.internal.domain.Booking;
import com.playchale.api.venues.internal.domain.Venue;
import com.playchale.api.venues.internal.domain.VenueDetails;
import com.playchale.api.venues.internal.repository.BookingRepository;
import com.playchale.api.venues.internal.repository.VenueRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Partner venues: finding them, listing and editing them, and what's free when. */
@Service
public class VenueService {

	/** Search shows at most this many; players narrow it by typing. */
	private static final int SEARCH_LIMIT = 50;

	private static final Duration SLOT = Duration.ofHours(1);

	private final VenueRepository venues;

	private final BookingRepository bookings;

	private final UserDirectory users;

	private final Clock clock;

	VenueService(VenueRepository venues, BookingRepository bookings, UserDirectory users, Clock clock) {
		this.venues = venues;
		this.bookings = bookings;
		this.users = users;
		this.clock = clock;
	}

	/** venues.search: listed venues whose name or area matches; every listed venue for a blank query. */
	@Transactional(readOnly = true)
	public List<VenueResponse> search(String query) {
		var pattern = "%" + (query == null ? "" : query.strip().toLowerCase(Locale.ROOT)) + "%";
		return venues.searchListed(pattern, Limit.of(SEARCH_LIMIT)).stream().map(v -> VenueResponse.of(v, null)).toList();
	}

	/** venues.get: the venue with its owner. */
	@Transactional(readOnly = true)
	public Optional<VenueResponse> get(UUID id) {
		return venues.findById(id).map(v -> VenueResponse.of(v, users.find(v.getOwnerId()).map(o -> o.toPublic()).orElse(null)));
	}

	/** venues.mine */
	@Transactional(readOnly = true)
	public List<VenueResponse> mine(UUID owner) {
		return venues.findByOwnerIdOrderByCreatedAt(owner).stream().map(v -> VenueResponse.of(v, null)).toList();
	}

	/** venues.create: listed in the owner's market straight away. */
	@Transactional
	public VenueResponse create(UUID owner, VenueDetails details) {
		var venue = new Venue(owner, Market.get(Market.DEFAULT));
		venue.apply(details, clock.instant());
		return VenueResponse.of(venues.save(venue), null);
	}

	/** venues.update: owner only. A pitch with bookings still to come can't be removed. */
	@Transactional
	public VenueResponse update(UUID owner, UUID venueId, VenueDetails details) {
		var venue = owned(venueId, owner);
		var removed = venue.pitchesRemovedBy(details);
		if (!removed.isEmpty() && bookings.existsByPitchIdInAndStatusAndEndsAtAfter(removed, Booking.CONFIRMED, clock.instant())) {
			throw BusinessException.conflict("A pitch you removed still has upcoming bookings. Cancel or move them first.");
		}
		venue.apply(details, clock.instant());
		return VenueResponse.of(venues.saveAndFlush(venue), null);
	}

	/**
	 * venues.availability: every bookable hour on every pitch for one local day, marked with why
	 * it's taken. Closed days have none.
	 */
	@Transactional(readOnly = true)
	public List<SlotResponse> availability(UUID venueId, LocalDate date) {
		var venue = venues.findById(venueId).orElseThrow(VenueService::notFound);
		var hours = venue.hoursOn(date.getDayOfWeek());
		if (hours.isEmpty()) {
			return List.of();
		}
		var midnight = date.atStartOfDay(venue.zone()).toInstant();
		var open = midnight.plus(Duration.ofMinutes(hours.get().openMinute()));
		var close = midnight.plus(Duration.ofMinutes(hours.get().closeMinute()));
		var taken = bookings.findConfirmedBetween(venue.getId(), open, close);
		var now = clock.instant();

		var slots = new ArrayList<SlotResponse>();
		for (var pitch : venue.activePitches()) {
			for (var start = open; !start.plus(SLOT).isAfter(close); start = start.plus(SLOT)) {
				var from = start;
				var end = start.plus(SLOT);
				var clash = taken.stream()
					.filter(b -> b.getPitchId().equals(pitch.getId()) && b.getStartsAt().isBefore(end) && b.getEndsAt().isAfter(from))
					.findFirst();
				String reason = from.isBefore(now) ? "past" : clash.map(b -> b.isBlock() ? "blocked" : "booked").orElse(null);
				slots.add(new SlotResponse(pitch.getId(), from, end, pitch.getPricePerHour(), reason == null, reason));
			}
		}
		return slots;
	}

	Venue owned(UUID venueId, UUID userId) {
		var venue = venues.findById(venueId).orElseThrow(VenueService::notFound);
		if (!venue.isOwnedBy(userId)) {
			throw BusinessException.conflict("Only the venue’s owner can do that.");
		}
		return venue;
	}

	static BusinessException notFound() {
		return BusinessException.notFound("That venue could not be found.");
	}

}
