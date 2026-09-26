package com.playchale.api.venues.internal.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

/**
 * A pitch held for a time: by a game booked in the app, by the manager for someone in person (at the
 * gate, on the phone), or blocked by the manager (maintenance, private holds). Overlaps are refused
 * by the database itself (bookings_no_overlap), so two requests racing for one slot can never both
 * win, and that holds when a booking is moved too.
 */
@Entity
@Table(name = "bookings")
public class Booking {

	public static final String GAME = "game";

	public static final String BLOCK = "block";

	/** Taken by the manager for someone in person: named, priced, and paid in cash or MoMo (or still owed). */
	public static final String IN_PERSON = "in-person";

	public static final String CASH = "cash";

	public static final String MOMO = "momo";

	public static final String CONFIRMED = "confirmed";

	public static final String CANCELLED = "cancelled";

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	private UUID venueId;

	private UUID pitchId;

	private Instant startsAt;

	private Instant endsAt;

	private String kind;

	private UUID gameId;

	private UUID bookedBy;

	private long price;

	private String note;

	/** Who an in-person booking is for. */
	private String customerName;

	/** Their number (E.164), for the manager to reach them. */
	private String customerPhone;

	/** {@link #CASH} or {@link #MOMO} once an in-person booking is paid; null while it's owed. */
	private String paidVia;

	private String status;

	private Instant createdAt;

	protected Booking() {
	}

	private Booking(UUID venueId, UUID pitchId, Instant startsAt, Instant endsAt, String kind, UUID gameId, UUID bookedBy,
			long price, String note, Instant now) {
		this.venueId = venueId;
		this.pitchId = pitchId;
		this.startsAt = startsAt;
		this.endsAt = endsAt;
		this.kind = kind;
		this.gameId = gameId;
		this.bookedBy = bookedBy;
		this.price = price;
		this.note = note;
		this.status = CONFIRMED;
		this.createdAt = now;
	}

	/** A pitch held by a game its host created. */
	public static Booking forGame(Venue venue, Pitch pitch, Instant startsAt, Instant endsAt, UUID gameId, UUID host, Instant now) {
		long minutes = Duration.between(startsAt, endsAt).toMinutes();
		return new Booking(venue.getId(), pitch.getId(), startsAt, endsAt, GAME, gameId, host, pitch.priceFor(minutes), null, now);
	}

	/** Time the owner holds for themselves: walk-ins, maintenance, a regular team paying cash. */
	public static Booking block(Venue venue, UUID pitchId, Instant startsAt, Instant endsAt, String note, Instant now) {
		return new Booking(venue.getId(), pitchId, startsAt, endsAt, BLOCK, null, venue.getOwnerId(), 0, note, now);
	}

	/**
	 * Someone the manager is booking in person. {@code price} is what they agreed (usually the pitch's
	 * rate); {@code paidVia} is null while it's owed.
	 */
	public static Booking inPerson(Venue venue, UUID pitchId, Instant startsAt, Instant endsAt, String customerName, String customerPhone,
			long price, String paidVia, String note, Instant now) {
		var booking = new Booking(venue.getId(), pitchId, startsAt, endsAt, IN_PERSON, null, venue.getOwnerId(), price, note, now);
		booking.customerName = customerName;
		booking.customerPhone = customerPhone;
		booking.paidVia = paidVia;
		return booking;
	}

	/** The manager's changes to an in-person booking: who, what it costs, whether it's paid, the note. */
	public void describe(String customerName, String customerPhone, long price, String paidVia, String note) {
		this.customerName = customerName;
		this.customerPhone = customerPhone;
		this.price = price;
		this.paidVia = paidVia;
		this.note = note;
	}

	/** To another pitch or time at the same venue. The database refuses it if it overlaps anything. */
	public void moveTo(UUID pitchId, Instant startsAt, Instant endsAt) {
		this.pitchId = pitchId;
		this.startsAt = startsAt;
		this.endsAt = endsAt;
	}

	public void cancel() {
		status = CANCELLED;
	}

	public boolean isBlock() {
		return BLOCK.equals(kind);
	}

	public boolean isGame() {
		return GAME.equals(kind);
	}

	public boolean isInPerson() {
		return IN_PERSON.equals(kind);
	}

	public String getCustomerName() {
		return customerName;
	}

	public String getCustomerPhone() {
		return customerPhone;
	}

	public String getPaidVia() {
		return paidVia;
	}

	public UUID getId() {
		return id;
	}

	public UUID getVenueId() {
		return venueId;
	}

	public UUID getPitchId() {
		return pitchId;
	}

	public Instant getStartsAt() {
		return startsAt;
	}

	public Instant getEndsAt() {
		return endsAt;
	}

	public String getKind() {
		return kind;
	}

	public UUID getGameId() {
		return gameId;
	}

	public UUID getBookedBy() {
		return bookedBy;
	}

	public long getPrice() {
		return price;
	}

	public String getNote() {
		return note;
	}

	public String getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
