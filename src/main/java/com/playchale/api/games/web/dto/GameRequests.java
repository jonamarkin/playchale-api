package com.playchale.api.games.web.dto;

import java.util.List;
import java.util.UUID;

import com.playchale.api.games.internal.service.GameResponse;

/** The small request and response bodies of the game endpoints. */
public final class GameRequests {

	private GameRequests() {
	}

	/** {"reason": "Pitch flooded"} */
	public record CancelRequest(String reason) {
	}

	/** {"userIds": [...]} */
	public record InviteRequest(List<UUID> userIds) {
	}

	/** {"invited": 3} */
	public record InviteResponse(int invited) {
	}

	/** {"name": "Kofi from work", "phone": "024..."} */
	public record GuestRequest(String name, String phone) {
	}

	/** {"game": {...}, "token": "..."}: the token goes in the claim link the host sends. */
	public record GuestResponse(GameResponse game, String token) {
	}

	/** {"token": "..."} from the claim link. */
	public record ClaimRequest(String token) {
	}

	/** {"userIds": [...]}, or no body for everyone unpaid. */
	public record RemindRequest(List<UUID> userIds) {
	}

	/** {"reminded": 2} */
	public record RemindResponse(int reminded) {
	}

}
