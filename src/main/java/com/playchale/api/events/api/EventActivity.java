package com.playchale.api.events.api;

import java.util.List;
import java.util.UUID;

/**
 * What happens in an event that someone should hear about. Only people taking part with their own
 * account can be told anything; names an admin typed in have nobody to tell.
 */
public final class EventActivity {

	private EventActivity() {
	}

	/** Someone was given a game to run. */
	public record CoordinatorAdded(UUID eventId, String eventName, UUID gameId, String gameName, List<UUID> recipients, UUID actorId) {
	}

	/** Someone was put into a game: on their own (no entry name), in a pair, or in a team. */
	public record EntryMade(UUID eventId, String eventName, UUID gameId, String gameName, String entryName, List<UUID> recipients,
			UUID actorId) {
	}

	/**
	 * Who plays whom first, each recipient their own match: after a game's draw ({@code what} "draw"),
	 * its times being planned ("times"), or its knockout being made from its pools ("knockout").
	 */
	public record DrawMade(UUID eventId, String eventName, UUID gameId, String gameName, List<FirstUp> recipients, UUID actorId,
			String what) {
	}

	/** Who someone meets first (or which heat they're in, with no opponent), and when and where, as far as that's known. */
	public record FirstUp(UUID userId, String opponent, String when) {
	}

	/** A result that involves them was recorded. */
	public record ResultRecorded(UUID eventId, String eventName, UUID gameId, String gameName, String summary, List<UUID> recipients,
			UUID actorId) {
	}

	/** The event is over: the overall winner, for everyone taking part. */
	/** The organisers told everyone something. */
	public record Announced(UUID eventId, String eventName, String body, List<UUID> recipients, UUID actorId) {
	}

	public record EventFinished(UUID eventId, String eventName, String winner, List<UUID> recipients, UUID actorId) {
	}

}
