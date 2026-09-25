package com.playchale.api.venues.internal.service;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.playchale.api.venues.internal.domain.Booking;

/**
 * A booking as the web app's Booking type. In an owner's schedule it's a BookingView: the pitch and
 * booker named, and for games the title and how many are in.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BookingResponse(UUID id, UUID venueId, UUID pitchId, Instant startsAt, Instant endsAt, String kind,
		UUID gameId, UUID bookedBy, long price, String note, String status, Instant createdAt, String pitchName,
		String bookedByName, String gameTitle, Integer players) {

	static BookingResponse of(Booking b) {
		return of(b, null, null, null, null);
	}

	static BookingResponse of(Booking b, String pitchName, String bookedByName, String gameTitle, Integer players) {
		return new BookingResponse(b.getId(), b.getVenueId(), b.getPitchId(), b.getStartsAt(), b.getEndsAt(), b.getKind(),
				b.getGameId(), b.getBookedBy(), b.getPrice(), b.getNote(), b.getStatus(), b.getCreatedAt(), pitchName,
				bookedByName, gameTitle, players);
	}

}
