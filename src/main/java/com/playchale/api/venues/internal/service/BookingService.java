package com.playchale.api.venues.internal.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.venues.api.BookedGames;
import com.playchale.api.venues.api.PitchBooking;
import com.playchale.api.venues.api.PitchBookings;
import com.playchale.api.venues.api.VenueSummary;
import com.playchale.api.venues.internal.domain.Booking;
import com.playchale.api.venues.internal.domain.Pitch;
import com.playchale.api.venues.internal.repository.BookingRepository;
import com.playchale.api.venues.internal.repository.VenueRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Holding pitches: for games (through {@link PitchBookings}), and the owner's own blocks and schedule. */
@Service
public class BookingService implements PitchBookings {

	private final BookingRepository bookings;

	private final VenueRepository venues;

	private final VenueService venueService;

	private final UserDirectory users;

	private final ObjectProvider<BookedGames> bookedGames;

	private final Clock clock;

	BookingService(BookingRepository bookings, VenueRepository venues, VenueService venueService, UserDirectory users,
			ObjectProvider<BookedGames> bookedGames, Clock clock) {
		this.bookings = bookings;
		this.venues = venues;
		this.venueService = venueService;
		this.users = users;
		this.bookedGames = bookedGames;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<VenueSummary> findVenue(UUID venueId) {
		return venues.findById(venueId).map(v -> new VenueSummary(v.getId(), v.getName(), v.getArea(), v.getOwnerId()));
	}

	@Override
	@Transactional
	public PitchBooking bookForGame(UUID venueId, UUID pitchId, Instant startsAt, Instant endsAt, UUID gameId, UUID host) {
		var venue = venues.findById(venueId).orElseThrow(VenueService::notFound);
		var pitch = venue.activePitch(pitchId)
			.orElseThrow(() -> BusinessException.invalid("That pitch isn’t available at this venue any more."));
		if (!venue.isOpen(startsAt, endsAt)) {
			throw BusinessException.invalid("%s is closed at that time.".formatted(venue.getName()));
		}
		var taken = "%s was just booked for that time. Pick another slot.".formatted(pitch.getName());
		if (bookings.findClash(pitchId, startsAt, endsAt).isPresent()) {
			throw BusinessException.conflict(taken);
		}
		var booking = hold(Booking.forGame(venue, pitch, startsAt, endsAt, gameId, host, clock.instant()), taken);
		return new PitchBooking(booking.getId(), venue.getId(), venue.getName(), venue.getArea(), venue.getOwnerId(),
				pitch.getId(), pitch.getName(), booking.getPrice());
	}

	@Override
	@Transactional
	public void releaseForGame(UUID gameId) {
		bookings.findByGameIdAndStatus(gameId, Booking.CONFIRMED).forEach(Booking::cancel);
	}

	/** venues.schedule: owner only. */
	@Transactional(readOnly = true)
	public List<BookingResponse> schedule(UUID owner, UUID venueId, Instant from, Instant to) {
		var venue = venueService.owned(venueId, owner);
		var found = bookings.findConfirmedBetween(venue.getId(), from, to);
		var pitchNames = venue.activePitches().stream().collect(Collectors.toMap(Pitch::getId, Pitch::getName));
		var bookers = users.findAll(found.stream().map(Booking::getBookedBy).distinct().toList());
		var games = describeGames(found.stream().map(Booking::getGameId).filter(id -> id != null).distinct().toList());
		return found.stream().map(b -> {
			var game = b.getGameId() == null ? null : games.get(b.getGameId());
			return BookingResponse.of(b, pitchNames.getOrDefault(b.getPitchId(), "Pitch"),
					bookers.containsKey(b.getBookedBy()) ? bookers.get(b.getBookedBy()).name() : "Someone",
					game == null ? null : game.title(), game == null ? null : game.players());
		}).toList();
	}

	/** venues.block: owner only. */
	@Transactional
	public BookingResponse block(UUID owner, UUID venueId, UUID pitchId, Instant startsAt, Instant endsAt, String note) {
		var venue = venueService.owned(venueId, owner);
		if (venue.activePitch(pitchId).isEmpty()) {
			throw BusinessException.invalid("Pick one of your pitches.");
		}
		if (!endsAt.isAfter(startsAt)) {
			throw BusinessException.invalid("The end time must be after the start time.");
		}
		var clash = bookings.findClash(pitchId, startsAt, endsAt);
		if (clash.isPresent()) {
			throw BusinessException.conflict(clash.get().isBlock() ? "That time is already blocked." : "That time is already booked for a game.");
		}
		var cleanNote = note == null || note.isBlank() ? null : note.strip();
		return BookingResponse.of(hold(Booking.block(venue, pitchId, startsAt, endsAt, cleanNote, clock.instant()), "That time was just booked."));
	}

	/** venues.cancelBlock: owner only. Game bookings go when their game is called off. */
	@Transactional
	public void cancelBlock(UUID owner, UUID bookingId) {
		var booking = bookings.findById(bookingId).orElseThrow(() -> BusinessException.notFound("That booking no longer exists."));
		venueService.owned(booking.getVenueId(), owner);
		if (!booking.isBlock()) {
			throw BusinessException.conflict("Game bookings are cancelled by the game’s host.");
		}
		booking.cancel();
	}

	/**
	 * Saves a booking straight away, so if another request took the slot a moment ago, the database's
	 * no-overlap rule refuses it here with a clear message.
	 */
	private Booking hold(Booking booking, String takenMessage) {
		try {
			return bookings.saveAndFlush(booking);
		}
		catch (DataIntegrityViolationException e) {
			throw BusinessException.conflict(takenMessage);
		}
	}

	private Map<UUID, BookedGames.BookedGame> describeGames(Collection<UUID> gameIds) {
		if (gameIds.isEmpty()) {
			return Map.of();
		}
		return bookedGames.stream().findFirst().map(g -> g.describe(gameIds)).orElseGet(Map::of);
	}

}
