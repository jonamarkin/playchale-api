package com.playchale.api.venues.internal.service;

import java.time.Clock;
import java.time.Duration;
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
import com.playchale.api.venues.api.GameBookingMoved;
import com.playchale.api.venues.api.PitchBooking;
import com.playchale.api.venues.api.PitchBookings;
import com.playchale.api.venues.api.VenueSummary;
import com.playchale.api.venues.internal.domain.Booking;
import com.playchale.api.venues.internal.domain.Pitch;
import com.playchale.api.venues.internal.domain.Venue;
import com.playchale.api.venues.internal.repository.BookingRepository;
import com.playchale.api.venues.internal.repository.VenueRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
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

	private final ApplicationEventPublisher events;

	private final Clock clock;

	BookingService(BookingRepository bookings, VenueRepository venues, VenueService venueService, UserDirectory users,
			ObjectProvider<BookedGames> bookedGames, ApplicationEventPublisher events, Clock clock) {
		this.bookings = bookings;
		this.venues = venues;
		this.venueService = venueService;
		this.users = users;
		this.bookedGames = bookedGames;
		this.events = events;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<VenueSummary> findVenue(UUID venueId) {
		return venues.findById(venueId).map(v -> new VenueSummary(v.getId(), v.getName(), v.getArea(), v.getOwnerId(), v.getCountry(), v.getCurrency(), v.getTimezone()));
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, String> mapLinks(Collection<UUID> venueIds) {
		if (venueIds.isEmpty()) {
			return Map.of();
		}
		return venues.findAllById(venueIds).stream().filter(v -> v.getMapUrl() != null)
			.collect(Collectors.toMap(Venue::getId, Venue::getMapUrl));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<String> problemForGame(UUID venueId, UUID pitchId, Instant startsAt, Instant endsAt, UUID gameId) {
		var venue = venues.findById(venueId).orElse(null);
		if (venue == null) return Optional.of("That partner venue is no longer available.");
		if (venue.activePitch(pitchId).isEmpty()) return Optional.of("That pitch is no longer available at this venue.");
		if (!venue.isOpen(startsAt, endsAt)) return Optional.of("%s is closed at that time.".formatted(venue.getName()));
		var own = bookings.findByGameIdAndStatus(gameId, Booking.CONFIRMED).stream().findFirst();
		var clash = own.isPresent() ? bookings.findClashExcept(pitchId, startsAt, endsAt, own.get().getId())
			: bookings.findClash(pitchId, startsAt, endsAt);
		return clash.map(BookingService::clashMessage);
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
		bookings.findClash(pitchId, startsAt, endsAt).ifPresent(clash -> {
			throw BusinessException.conflict(clashMessage(clash));
		});
		return BookingResponse.of(hold(Booking.block(venue, pitchId, startsAt, endsAt, optional(note, 120), clock.instant()),
				"That time was just booked."));
	}

	/**
	 * venues.bookInPerson: owner only. Someone booking at the gate or on the phone: named, priced
	 * (the pitch's rate unless the manager agreed another), and paid in cash or MoMo, or still owed.
	 */
	@Transactional
	public BookingResponse bookInPerson(UUID owner, UUID venueId, UUID pitchId, Instant startsAt, Instant endsAt, InPersonDetails details) {
		var venue = venueService.owned(venueId, owner);
		var pitch = venue.activePitch(pitchId).orElseThrow(() -> BusinessException.invalid("Pick one of your pitches."));
		checkTimes(startsAt, endsAt);
		bookings.findClash(pitchId, startsAt, endsAt).ifPresent(clash -> {
			throw BusinessException.conflict(clashMessage(clash));
		});
		var price = details.price() == null ? pitch.priceFor(Duration.between(startsAt, endsAt).toMinutes()) : details.price();
		var booking = Booking.inPerson(venue, pitchId, startsAt, endsAt, name(details.customerName()), phone(venue, details.customerPhone()),
				price(price), paidVia(details.paidVia()), optional(details.note(), 120), clock.instant());
		return BookingResponse.of(hold(booking, "That time was just booked."));
	}

	/** venues.updateBooking: owner only. Who an in-person booking is for, its price, whether it's paid, its note. */
	@Transactional
	public BookingResponse updateInPerson(UUID owner, UUID bookingId, InPersonDetails details) {
		var booking = ownedBooking(owner, bookingId);
		if (!booking.isInPerson()) {
			throw BusinessException.conflict("Only in-person bookings have these details.");
		}
		var venue = venueService.owned(booking.getVenueId(), owner);
		booking.describe(details.customerName() == null ? booking.getCustomerName() : name(details.customerName()),
				details.customerPhone() == null ? booking.getCustomerPhone() : phone(venue, details.customerPhone()),
				details.price() == null ? booking.getPrice() : price(details.price()),
				details.paidVia() == null ? booking.getPaidVia() : paidVia(details.paidVia()),
				details.note() == null ? booking.getNote() : optional(details.note(), 120));
		return BookingResponse.of(booking);
	}

	/**
	 * venues.moveBooking: owner only. To another pitch or time at the same venue, clash-free. An
	 * in-person booking or a block can change length too. A game keeps its length and stays on a pitch
	 * for its sport within opening hours; the game's kick-off moves with it and everyone in it is told
	 * (the games module does that, in this same transaction).
	 */
	@Transactional
	public BookingResponse move(UUID owner, UUID bookingId, UUID pitchId, Instant startsAt, Instant endsAt) {
		var booking = ownedBooking(owner, bookingId);
		var venue = venueService.owned(booking.getVenueId(), owner);
		var now = clock.instant();
		if (!booking.getEndsAt().isAfter(now)) {
			throw BusinessException.conflict("That booking is over, so it can’t be moved.");
		}
		var pitch = venue.activePitch(pitchId).orElseThrow(() -> BusinessException.invalid("Pick one of your pitches."));
		checkTimes(startsAt, endsAt);
		if (booking.isGame()) {
			if (booking.getStartsAt().isBefore(now)) {
				throw BusinessException.conflict("That game has started, so it can’t be moved.");
			}
			if (!Duration.between(startsAt, endsAt).equals(Duration.between(booking.getStartsAt(), booking.getEndsAt()))) {
				throw BusinessException.invalid("A game keeps its length. Move its start instead.");
			}
			var from = venue.activePitch(booking.getPitchId()).map(Pitch::getSport).orElse(pitch.getSport());
			if (!from.equals(pitch.getSport())) {
				throw BusinessException.invalid("Pick a pitch for the same sport.");
			}
			if (!venue.isOpen(startsAt, endsAt)) {
				throw BusinessException.invalid("%s is closed at that time.".formatted(venue.getName()));
			}
		}
		bookings.findClashExcept(pitchId, startsAt, endsAt, booking.getId()).ifPresent(clash -> {
			throw BusinessException.conflict(clashMessage(clash));
		});
		booking.moveTo(pitchId, startsAt, endsAt);
		hold(booking, "That time was just booked.");
		if (booking.isGame()) {
			events.publishEvent(new GameBookingMoved(booking.getGameId(), venue.getId(), venue.getName(), pitchId, pitch.getName(), startsAt, endsAt));
		}
		return BookingResponse.of(booking);
	}

	/** venues.cancelBooking: owner only, for blocks and in-person bookings. Game bookings go when their game is called off. */
	@Transactional
	public void cancel(UUID owner, UUID bookingId) {
		var booking = ownedBooking(owner, bookingId);
		if (booking.isGame()) {
			throw BusinessException.conflict("Game bookings are cancelled by the game’s host.");
		}
		booking.cancel();
	}

	private Booking ownedBooking(UUID owner, UUID bookingId) {
		var booking = bookings.findById(bookingId).filter(b -> Booking.CONFIRMED.equals(b.getStatus()))
			.orElseThrow(() -> BusinessException.notFound("That booking no longer exists."));
		venueService.owned(booking.getVenueId(), owner);
		return booking;
	}

	private void checkTimes(Instant startsAt, Instant endsAt) {
		if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) {
			throw BusinessException.invalid("The end time must be after the start time.");
		}
		if (!endsAt.isAfter(clock.instant())) {
			throw BusinessException.invalid("That time has already passed.");
		}
	}

	private static String clashMessage(Booking clash) {
		if (clash.isBlock()) {
			return "That time is already blocked.";
		}
		return clash.isInPerson() ? "That time is already booked in person." : "That time is already booked for a game.";
	}

	private static String name(String name) {
		var clean = name == null ? "" : name.strip();
		if (clean.isEmpty() || clean.length() > 60) {
			throw BusinessException.invalid("Say who the booking is for.");
		}
		return clean;
	}

	private static String phone(Venue venue, String phone) {
		if (phone == null || phone.isBlank()) {
			return null;
		}
		var market = venue.market();
		return market.normalisePhone(phone)
			.orElseThrow(() -> BusinessException.invalid("Enter a valid %s number, or leave it blank.".formatted(market.countryName())));
	}

	private static long price(long price) {
		if (price < 0) {
			throw BusinessException.invalid("The price can’t be less than nothing.");
		}
		return price;
	}

	/** "cash", "momo", or "" (still owed, which is stored as null). */
	private static String paidVia(String paidVia) {
		if (paidVia == null || paidVia.isBlank()) {
			return null;
		}
		if (!Booking.CASH.equals(paidVia) && !Booking.MOMO.equals(paidVia)) {
			throw BusinessException.invalid("Say whether it was paid in cash or by mobile money.");
		}
		return paidVia;
	}

	private static String optional(String text, int max) {
		var clean = text == null ? "" : text.strip();
		return clean.isEmpty() ? null : clean.substring(0, Math.min(clean.length(), max));
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
