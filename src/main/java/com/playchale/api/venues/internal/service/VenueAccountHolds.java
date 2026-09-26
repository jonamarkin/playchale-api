package com.playchale.api.venues.internal.service;

import java.util.Optional;
import java.util.UUID;

import com.playchale.api.users.api.AccountHolds;
import com.playchale.api.venues.internal.repository.VenueRepository;
import org.springframework.stereotype.Component;

/** A venue owner's account can't go while players can still book their pitches. */
@Component
class VenueAccountHolds implements AccountHolds {

	private final VenueRepository venues;

	VenueAccountHolds(VenueRepository venues) {
		this.venues = venues;
	}

	@Override
	public Optional<String> reasonToWait(UUID userId) {
		return venues.existsByOwnerId(userId)
				? Optional.of("You run a venue on PlayChale. Get in touch and we’ll close it with you before your account goes.")
				: Optional.empty();
	}

}
