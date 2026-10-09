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

	/** A game's draw is out: who plays whom first. Each recipient gets their own first match. */
	public record DrawMade(UUID eventId, String eventName, UUID gameId, String gameName, List<FirstUp> recipients, UUID actorId) {
	}

	/** Who someone meets first, and when and where, if the coordinator said. */
	public record FirstUp(UUID userId, String opponent, String when) {
	}

	/** A result that involves them was recorded. */
	public record ResultRecorded(UUID eventId, String eventName, UUID gameId, String gameName, String summary, List<UUID> recipients,
			UUID actorId) {
	}

	/** The event is over: the overall winner, for everyone taking part. */
	public record EventFinished(UUID eventId, String eventName, String winner, List<UUID> recipients, UUID actorId) {
	}

}
