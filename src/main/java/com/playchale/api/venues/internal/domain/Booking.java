package com.playchale.api.venues.internal.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

/**
 * A pitch held for a time: by a game, or blocked by the owner. Overlaps are refused by the database
 * itself (bookings_no_overlap), so two requests racing for one slot can never both win.
 */
@Entity
@Table(name = "bookings")
public class Booking {

	public static final String GAME = "game";

	public static final String BLOCK = "block";

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

	public void cancel() {
		status = CANCELLED;
	}

	public boolean isBlock() {
		return BLOCK.equals(kind);
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
