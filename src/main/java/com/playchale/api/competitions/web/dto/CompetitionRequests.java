package com.playchale.api.competitions.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.playchale.api.competitions.internal.domain.CompetitionDetails;
import jakarta.validation.constraints.NotNull;

/** The request bodies of the competition endpoints. */
public final class CompetitionRequests {

	private CompetitionRequests() {
	}

	/** The web app's NewCompetitionInput. */
	public record NewCompetition(String name, String sport, String format, @NotNull(message = "Say where the fixtures are played.") Venue venue,
			@NotNull(message = "Pick a first matchday in the future.") Instant startsAt, int durationMinutes, String playerLists,
			String timezone, String structure, UUID organisationId) {

		public CompetitionDetails toDetails() {
			return new CompetitionDetails(name, sport, format, "listed".equals(venue.kind()) ? "listed" : "unlisted", venue.venueId(),
					venue.name(), venue.area(), venue.mapUrl(), startsAt, durationMinutes, playerLists, timezone, structure);
		}

	}

	/** The web app's VenueRef. */
	public record Venue(String kind, UUID venueId, String name, String area, UUID pitchId, String pitchName, String mapUrl) {
	}

	/**
	 * {"teamId"} to enter an existing team, or {"name", "captainId"?, "playerIds"?} for a new one the
	 * organiser sets up.
	 */
	public record NewTeam(UUID teamId, String name, UUID captainId, List<UUID> playerIds) {
	}

	/** {"userIds": [...]} */
	public record Players(List<UUID> userIds) {
	}

	/** {"accept": true} */
	public record Answer(boolean accept) {
	}

	/** {"token": "..."} from the squad link. */
	public record SquadLink(String token) {
	}

}
