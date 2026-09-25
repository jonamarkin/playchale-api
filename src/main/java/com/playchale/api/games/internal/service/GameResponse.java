package com.playchale.api.games.internal.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.playchale.api.users.api.UserSummary;

/**
 * A game as the web app's GameView type: the game, with the people it mentions filled in, each
 * player's share and the spots left.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GameResponse(UUID id, String sport, String format, String title, Instant startsAt, int durationMinutes,
		VenueRef venue, int capacity, long totalCost, String currency, String visibility, UUID hostId, String notes,
		List<ParticipantResponse> participants, String status, Instant createdAt, Instant cancelledAt, String cancelReason,
		UserSummary host, List<PlayerResponse> players, long share, int spotsLeft) {

	/** Where it's played: {kind: "listed", venueId, name, area, pitchId?, pitchName?} or {kind: "unlisted", name, area?}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record VenueRef(String kind, UUID venueId, String name, String area, UUID pitchId, String pitchName) {
	}

	/**
	 * A spot. {@code userId} is "" while it's held for a guest. A guest's {@code token} is the
	 * spot's public ID, never the secret in the claim link.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ParticipantResponse(String userId, Instant joinedAt, boolean paid, UUID paymentId, String paidVia,
			Instant remindedAt, Guest guest) {
	}

	/** A guest spot as the web app's Guest type. The number is only shown to the host. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Guest(String name, String phone, String token, UUID addedBy) {
	}

	/**
	 * Someone holding a spot, as the web app's User type plus whether they've paid. A guest has an
	 * id of "guest:<token>" and the host's note as their name.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PlayerResponse(String id, String phone, String name, String handle, String avatar, String tint,
			String area, List<String> sports, String position, Instant createdAt, boolean onboarded, String payoutPhone,
			boolean paid, Guest guest) {
	}

}
