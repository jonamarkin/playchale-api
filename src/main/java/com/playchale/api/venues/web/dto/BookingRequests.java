package com.playchale.api.venues.web.dto;

import java.time.Instant;
import java.util.UUID;

import com.playchale.api.venues.internal.service.InPersonDetails;
import jakarta.validation.constraints.NotNull;

/** The web app's booking inputs for a venue's manager. */
public final class BookingRequests {

	private BookingRequests() {
	}

	/** venues.bookInPerson: someone booking at the gate or on the phone. {@code price} left out means the pitch's rate. */
	public record InPerson(@NotNull(message = "Pick one of your pitches.") UUID pitchId,
			@NotNull(message = "Pick a start time.") Instant startsAt, @NotNull(message = "Pick an end time.") Instant endsAt,
			String customerName, String customerPhone, Long price, String paidVia, String note) {

		public InPersonDetails details() {
			return new InPersonDetails(customerName, customerPhone, price, paidVia, note);
		}

	}

	/** venues.updateBooking: fields left out stay as they are; {@code paidVia: ""} means still owed. */
	public record Changes(String customerName, String customerPhone, Long price, String paidVia, String note) {

		public InPersonDetails details() {
			return new InPersonDetails(customerName, customerPhone, price, paidVia, note);
		}

	}

	/** venues.moveBooking: where and when it goes. */
	public record Move(@NotNull(message = "Pick one of your pitches.") UUID pitchId,
			@NotNull(message = "Pick a start time.") Instant startsAt, @NotNull(message = "Pick an end time.") Instant endsAt) {
	}

}
