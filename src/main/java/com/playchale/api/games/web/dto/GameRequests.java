package com.playchale.api.games.web.dto;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import com.playchale.api.games.api.GameResponse;
import com.playchale.api.games.internal.domain.SeriesChange;
import com.playchale.api.shared.error.BusinessException;

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

	/** {"teamId": "..."}: a team the host is in. */
	public record TeamInviteRequest(UUID teamId) {
	}

	/** {"accept": true} */
	public record InviteAnswer(boolean accept) {
	}

	/** {"showedUp": true}: the host saying whether a player turned up. */
	public record AttendanceRequest(boolean showedUp) {
	}

	/** {"body": "Running ten minutes late"} */
	public record SayRequest(String body) {
	}

	/** {"invited": 3} */
	public record InviteResponse(int invited) {
	}

	/** {"name": "Kofi from work", "phone": "024..."} */
	public record GuestRequest(String name, String phone) {
	}

	/**
	 * {"game": {...}, "token": "...", "spot": "..."}: the token goes in the claim link the host sends,
	 * or stays in the guest's browser; {@code spot} is the spot's ID as the game shows it (guest.token).
	 */
	public record GuestResponse(GameResponse game, String token, String spot) {
	}

	/** {"name", "phone", "email"?}: someone without an account taking a spot. */
	public record GuestJoinRequest(String name, String phone, String email) {
	}

	/** {"token": "..."} from the claim link. */
	public record ClaimRequest(String token) {
	}

	/** {"reason": "It was 3-2, not 2-2"} */
	public record DisputeRequest(String reason) {
	}

	/** {"userIds": [...]}, or no body for everyone unpaid. */
	public record RemindRequest(List<UUID> userIds) {
	}

	/** {"reminded": 2} */
	public record RemindResponse(int reminded) {
	}

	/**
	 * {"frequency": "weekly"}, "fortnightly", or {"frequency": "monthly", "weekOfMonth": 2} (-1 for the
	 * last). The day and time are the game's own.
	 */
	public record RepeatsRequest(String frequency, Integer weekOfMonth) {
	}

	/**
	 * What a host changes about a repeating game. {@code weekday} is ISO (1 is Monday) and
	 * {@code kickOff} "18:00", in the game's own time.
	 */
	public record SeriesChangeRequest(String title, int weekday, String kickOff, String frequency, Integer weekOfMonth, int durationMinutes,
			int capacity, long totalCost, String pricing, String visibility, String notes) {

		public SeriesChange toChange() {
			LocalTime time;
			try {
				time = LocalTime.parse(kickOff == null ? "" : kickOff.strip());
			}
			catch (DateTimeParseException e) {
				throw BusinessException.invalid("Pick a kick-off time.");
			}
			return new SeriesChange(title, weekday, time, frequency, weekOfMonth, durationMinutes, capacity, totalCost, pricing, visibility, notes);
		}

	}

}
