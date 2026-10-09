package com.playchale.api.events.internal.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What the event screens get, as JSON. The same shapes as {@code webapp/app/types/domain.ts}.
 *
 * <p>An event is small (a few hundred people, a few dozen games), so its page gets all of it in one
 * answer, and every change answers with the whole event again: the screen never has to work out
 * what else a change touched.
 */
public final class EventViews {

	private EventViews() {
	}

	/** One event in a list: a workspace's events, or the signed-in player's. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Summary(UUID id, UUID organisationId, String organisationName, String organisationColour, String name,
			LocalDate startsOn, LocalDate endsOn, String venueName, String status, int games, int people, String role) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Venue(String name, String area, String mapUrl) {
	}

	public record Brand(UUID id, String name, String primaryColour, String logoUrl) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Info(UUID id, UUID organisationId, String name, LocalDate startsOn, LocalDate endsOn, String timezone, String country,
			Venue venue, String status, boolean registrationOpen, List<Integer> placingPoints, Instant createdAt) {
	}

	/**
	 * Who's looking, and what they may do.
	 *
	 * @param role        admin (runs the workspace), staff (holds another seat in it), or player (only taking part)
	 * @param personId    their place in the event, if they have one
	 * @param coordinates the games they coordinate
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Viewer(String role, UUID personId, List<UUID> coordinates) {
	}

	public record Group(UUID id, String name, String colour, int position) {
	}

	/** Someone taking part. {@code userId} only when they joined with their own account. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Person(UUID id, String name, UUID groupId, UUID userId, String avatar, String source) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Coordinator(UUID userId, String name, String avatar) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Entry(UUID id, String name, UUID groupId, List<UUID> personIds, int seed, String status) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Game(UUID id, String discipline, String name, String category, String entryKind, Integer teamSize, String format,
			String scoring, Integer bestOf, boolean drawsAllowed, boolean thirdPlace, Integer heatSize, Integer advancePerHeat,
			String location, Instant startsAt, String status, int position, List<Coordinator> coordinators, List<Entry> entries,
			List<UUID> interested) {
	}

	/** The links only admins see: to join the event, and to put its board on a screen. */
	public record Links(String joinUrl, String boardUrl) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Detail(Info event, Brand organisation, Viewer viewer, List<Group> groups, List<Person> people, List<Game> games,
			Links links) {
	}

	/** One game, as someone deciding whether to join sees it. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record JoinGame(UUID id, String discipline, String name, String category, String entryKind, String status) {
	}

	/** What the signed-in player already has in the event they're joining. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Mine(UUID personId, UUID groupId, List<UUID> gameIds) {
	}

	/** The event behind a join link, before joining. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record JoinPreview(UUID id, String name, Brand organisation, LocalDate startsOn, LocalDate endsOn, Venue venue, String status,
			boolean registrationOpen, List<Group> groups, List<JoinGame> games, Mine mine) {
	}

}
