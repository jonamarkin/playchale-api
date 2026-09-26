package com.playchale.api.venues.internal.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.shared.TestClock;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.venues.internal.domain.Booking;
import com.playchale.api.venues.internal.domain.DayHours;
import com.playchale.api.venues.internal.domain.PitchDetails;
import com.playchale.api.venues.internal.domain.VenueDetails;
import com.playchale.api.venues.internal.repository.BookingRepository;
import com.playchale.api.venues.internal.repository.VenueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Booking pitches against a real Postgres, including its no-overlap rule. */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, BookingServiceTest.Clocks.class })
class BookingServiceTest {

	@TestConfiguration
	static class Clocks {

		@Bean
		@Primary
		TestClock testClock() {
			return new TestClock();
		}

	}

	/** A Saturday morning in Accra (UTC all year). */
	private static final Instant NOW = Instant.parse("2026-09-26T08:30:00Z");

	private static final Instant TEN = Instant.parse("2026-09-26T10:00:00Z");

	private static final Instant ELEVEN = Instant.parse("2026-09-26T11:00:00Z");

	@Autowired
	VenueService venues;

	@Autowired
	BookingService bookings;

	@Autowired
	UserDirectory users;

	@Autowired
	BookingRepository bookingRows;

	@Autowired
	VenueRepository venueRows;

	@Autowired
	TestClock clock;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TransactionTemplate tx;

	UUID owner;

	UUID host;

	VenueResponse osu;

	UUID pitchA;

	@BeforeEach
	void setUp() {
		jdbc.sql("TRUNCATE users, sign_in_codes, sessions, venues, pitches, bookings CASCADE").update();
		clock.set(NOW);
		owner = users.registerOrFind("+233244100200", "GH").id();
		host = users.registerOrFind("+233244555123", "GH").id();
		osu = venues.create(owner, new VenueDetails("Osu Astro Turf", "Osu, Accra", null, null, null, null,
				List.of(new PitchDetails(null, "Pitch A", "football", "5-a-side", "turf", 25_000),
						new PitchDetails(null, "Pitch B", "football", "5-a-side", "turf", 25_000)),
				Collections.nCopies(7, new DayHours("06:00", "23:00")), List.of()));
		pitchA = osu.pitches().getFirst().id();
	}

	@Test
	void aGameHoldsAPitchAndTheNextGameCantHaveIt() {
		var gameId = UUID.randomUUID();
		var booked = bookings.bookForGame(osu.id(), pitchA, TEN, ELEVEN, gameId, host);
		assertThat(booked.pitchName()).isEqualTo("Pitch A");
		assertThat(booked.price()).isEqualTo(25_000);

		assertThatThrownBy(() -> bookings.bookForGame(osu.id(), pitchA, TEN.plusSeconds(1800), ELEVEN.plusSeconds(1800), UUID.randomUUID(), host))
			.isInstanceOf(BusinessException.class)
			.hasMessage("Pitch A was just booked for that time. Pick another slot.");

		// Back to back is fine, and so is the other pitch.
		bookings.bookForGame(osu.id(), pitchA, ELEVEN, ELEVEN.plusSeconds(3600), UUID.randomUUID(), host);
		bookings.bookForGame(osu.id(), osu.pitches().get(1).id(), TEN, ELEVEN, UUID.randomUUID(), host);

		// Calling the game off frees the pitch.
		bookings.releaseForGame(gameId);
		bookings.bookForGame(osu.id(), pitchA, TEN, ELEVEN, UUID.randomUUID(), host);
	}

	@Test
	void theDatabaseRefusesOverlapsEvenIfTheCheckIsSkipped() {
		tx.executeWithoutResult(s -> {
			var venue = venueRows.findById(osu.id()).orElseThrow();
			var pitch = venue.activePitch(pitchA).orElseThrow();
			bookingRows.saveAndFlush(Booking.forGame(venue, pitch, TEN, ELEVEN, UUID.randomUUID(), host, NOW));
		});
		assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
			var venue = venueRows.findById(osu.id()).orElseThrow();
			var pitch = venue.activePitch(pitchA).orElseThrow();
			bookingRows.saveAndFlush(Booking.forGame(venue, pitch, TEN.plusSeconds(60), ELEVEN, UUID.randomUUID(), host, NOW));
		})).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void gamesMustFitTheOpeningHours() {
		assertThatThrownBy(() -> bookings.bookForGame(osu.id(), pitchA, Instant.parse("2026-09-26T22:30:00Z"),
				Instant.parse("2026-09-26T23:30:00Z"), UUID.randomUUID(), host))
			.hasMessage("Osu Astro Turf is closed at that time.");
	}

	@Test
	void availabilityShowsEveryHourAndWhyItsTaken() {
		bookings.bookForGame(osu.id(), pitchA, TEN, ELEVEN, UUID.randomUUID(), host);
		bookings.block(owner, osu.id(), pitchA, ELEVEN, ELEVEN.plusSeconds(3600), "Maintenance");

		var slots = venues.availability(osu.id(), LocalDate.parse("2026-09-26"));
		assertThat(slots).hasSize(2 * 17); // two pitches, 06:00 to 23:00
		var onA = slots.stream().filter(s -> s.pitchId().equals(pitchA)).toList();
		assertThat(onA.get(0).reason()).as("06:00 has gone by").isEqualTo("past");
		assertThat(onA.get(4).reason()).as("10:00").isEqualTo("booked");
		assertThat(onA.get(5).reason()).as("11:00").isEqualTo("blocked");
		assertThat(onA.get(6).available()).as("12:00").isTrue();
	}

	@Test
	void aPitchWithBookingsToComeCantBeRemoved() {
		bookings.bookForGame(osu.id(), pitchA, TEN, ELEVEN, UUID.randomUUID(), host);
		var onlyB = new VenueDetails("Osu Astro Turf", "Osu, Accra", null, null, null, null,
				List.of(new PitchDetails(osu.pitches().get(1).id(), "Pitch B", "football", "5-a-side", "turf", 25_000)),
				Collections.nCopies(7, new DayHours("06:00", "23:00")), List.of());
		assertThatThrownBy(() -> venues.update(owner, osu.id(), onlyB))
			.hasMessage("A pitch you removed still has upcoming bookings. Cancel or move them first.");

		clock.set(ELEVEN.plusSeconds(60));
		assertThat(venues.update(owner, osu.id(), onlyB).pitches()).extracting(VenueResponse.PitchResponse::name).containsExactly("Pitch B");
	}

	private static InPersonDetails walkIn(String name, Long price, String paidVia) {
		return new InPersonDetails(name, "024 111 2222", price, paidVia, null);
	}

	@Test
	void anInPersonBookingIsNamedPricedAndPaidOrOwed() {
		var booked = bookings.bookInPerson(owner, osu.id(), pitchA, TEN, ELEVEN, walkIn(" Labone Old Boys ", null, ""));
		assertThat(booked.kind()).isEqualTo("in-person");
		assertThat(booked.customerName()).isEqualTo("Labone Old Boys");
		assertThat(booked.customerPhone()).isEqualTo("+233241112222");
		assertThat(booked.price()).as("the pitch's rate, unless agreed otherwise").isEqualTo(25_000);
		assertThat(booked.paidVia()).as("still owed").isNull();

		// Players see the hour as taken; another booking can't have it.
		var slots = venues.availability(osu.id(), LocalDate.parse("2026-09-26"));
		assertThat(slots.stream().filter(sl -> sl.pitchId().equals(pitchA)).toList().get(4).reason()).isEqualTo("booked");
		assertThatThrownBy(() -> bookings.block(owner, osu.id(), pitchA, TEN, ELEVEN, null)).hasMessage("That time is already booked in person.");

		var paid = bookings.updateInPerson(owner, booked.id(), new InPersonDetails(null, null, 20_000L, "cash", "Regulars' rate"));
		assertThat(paid.paidVia()).isEqualTo("cash");
		assertThat(paid.price()).isEqualTo(20_000);
		assertThat(paid.customerName()).as("left as it was").isEqualTo("Labone Old Boys");
		assertThat(bookings.updateInPerson(owner, booked.id(), new InPersonDetails(null, null, null, "", null)).paidVia())
			.as("back to owed").isNull();

		assertThatThrownBy(() -> bookings.bookInPerson(owner, osu.id(), pitchA, ELEVEN, ELEVEN.plusSeconds(3600), walkIn(" ", null, null)))
			.hasMessage("Say who the booking is for.");
		assertThatThrownBy(() -> bookings.bookInPerson(owner, osu.id(), pitchA, ELEVEN, ELEVEN.plusSeconds(3600), walkIn("Ama", null, "card")))
			.hasMessage("Say whether it was paid in cash or by mobile money.");
		assertThatThrownBy(() -> bookings.bookInPerson(owner, osu.id(), pitchA, NOW.minusSeconds(7200), NOW.minusSeconds(3600), walkIn("Ama", null, null)))
			.hasMessage("That time has already passed.");
		assertThatThrownBy(() -> bookings.bookInPerson(host, osu.id(), pitchA, ELEVEN, ELEVEN.plusSeconds(3600), walkIn("Ama", null, null)))
			.hasMessage("Only the venue’s owner can do that.");

		bookings.cancel(owner, booked.id());
		assertThat(bookings.schedule(owner, osu.id(), TEN, ELEVEN)).isEmpty();
	}

	@Test
	void theManagerMovesBookingsToAnotherPitchOrTime() {
		var pitchB = osu.pitches().get(1).id();
		var walkIn = bookings.bookInPerson(owner, osu.id(), pitchA, TEN, ELEVEN, walkIn("Labone Old Boys", null, "momo"));
		var block = bookings.block(owner, osu.id(), pitchB, TEN, ELEVEN, "Maintenance");

		// Another time on the same pitch, longer too.
		var later = bookings.move(owner, walkIn.id(), pitchA, ELEVEN, ELEVEN.plusSeconds(7200));
		assertThat(later.startsAt()).isEqualTo(ELEVEN);
		assertThat(later.price()).as("what they agreed stays").isEqualTo(25_000);
		// Onto the other pitch, where the block is: refused; next to it: fine.
		assertThatThrownBy(() -> bookings.move(owner, walkIn.id(), pitchB, TEN, ELEVEN)).hasMessage("That time is already blocked.");
		assertThat(bookings.move(owner, block.id(), pitchB, ELEVEN.plusSeconds(7200), ELEVEN.plusSeconds(10_800)).pitchId()).isEqualTo(pitchB);
		// Moving a booking onto itself, a little later, doesn't clash with where it was.
		assertThat(bookings.move(owner, walkIn.id(), pitchA, ELEVEN.plusSeconds(1800), ELEVEN.plusSeconds(5400)).startsAt())
			.isEqualTo(ELEVEN.plusSeconds(1800));

		assertThatThrownBy(() -> bookings.move(host, walkIn.id(), pitchA, TEN, ELEVEN)).hasMessage("Only the venue’s owner can do that.");
		clock.set(ELEVEN.plusSeconds(6000));
		assertThatThrownBy(() -> bookings.move(owner, walkIn.id(), pitchA, ELEVEN.plusSeconds(7200), ELEVEN.plusSeconds(10_800)))
			.hasMessage("That booking is over, so it can’t be moved.");
	}

	@Test
	void onlyTheOwnerManagesTheVenue() {
		assertThatThrownBy(() -> bookings.block(host, osu.id(), pitchA, TEN, ELEVEN, null))
			.hasMessage("Only the venue’s owner can do that.");
		var block = bookings.block(owner, osu.id(), pitchA, TEN, ELEVEN, null);
		assertThatThrownBy(() -> bookings.cancel(host, block.id())).hasMessage("Only the venue’s owner can do that.");
		bookings.cancel(owner, block.id());
		assertThat(bookings.schedule(owner, osu.id(), TEN, ELEVEN)).isEmpty();
	}

}
