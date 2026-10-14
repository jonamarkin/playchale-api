package com.playchale.api.venues.internal.service;

import java.time.Clock;
import java.util.UUID;

import com.playchale.api.venues.api.VenueReview;
import com.playchale.api.venues.api.VenueReviewed;
import com.playchale.api.venues.internal.repository.VenueRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** VenueReview, for the admin desk: staff approve a venue, or decline it with what to change. */
@Service
class VenueReviewService implements VenueReview {

	private final VenueRepository venues;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	VenueReviewService(VenueRepository venues, ApplicationEventPublisher events, Clock clock) {
		this.venues = venues;
		this.events = events;
		this.clock = clock;
	}

	@Override
	@Transactional
	public void approve(UUID venueId, UUID staffId) {
		var venue = venues.findById(venueId).orElseThrow(VenueService::notFound);
		var wasLive = venue.isLive();
		venue.approve(staffId, clock.instant());
		venues.save(venue);
		if (!wasLive) {
			events.publishEvent(new VenueReviewed(venue.getId(), venue.getName(), venue.getOwnerId(), true, null, false));
		}
	}

	@Override
	@Transactional
	public void decline(UUID venueId, String note, UUID staffId) {
		var venue = venues.findById(venueId).orElseThrow(VenueService::notFound);
		var wasLive = venue.isLive();
		venue.decline(note, staffId, clock.instant());
		venues.save(venue);
		events.publishEvent(new VenueReviewed(venue.getId(), venue.getName(), venue.getOwnerId(), false, venue.getReviewNote(), wasLive));
	}

}
