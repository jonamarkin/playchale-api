package com.playchale.api.teams.web.dto;

import java.util.List;
import java.util.UUID;

/** The request bodies of the team endpoints. */
public final class TeamRequests {

	private TeamRequests() {
	}

	/** teams.create: {"name", "tint"?, "memberIds"?} */
	public record NewTeam(String name, String tint, List<UUID> memberIds) {
	}

	/** teams.update: fields left out stay as they are. {@code captainId} hands the armband over. */
	public record Changes(String name, String tint, UUID captainId) {
	}

	/** {"userIds": [...]} */
	public record Members(List<UUID> userIds) {
	}

	/** {"accept": true} */
	public record Answer(boolean accept) {
	}

	/** {"token": "..."} from the team's link. */
	public record Link(String token) {
	}

}
