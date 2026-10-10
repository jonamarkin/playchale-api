package com.playchale.api.events.internal.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.playchale.api.shared.maps.MapPin;

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
	public record Venue(String name, String area, String mapUrl, MapPin pin) {
	}

	public record Brand(UUID id, String name, String primaryColour, String logoUrl) {
	}

	/** The event itself. {@code publicPath} is its public page ("/e/hillview-games-day-k7q2"), when its admins turned one on. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Info(UUID id, UUID organisationId, String name, LocalDate startsOn, LocalDate endsOn, String timezone, String country,
			Venue venue, String status, boolean registrationOpen, List<Integer> placingPoints, Instant createdAt, Long entryFee, String currency,
			String publicPath, Long groupFee) {

		/** As the public page shows it: nothing about money. */
		Info forPublic() {
			return new Info(id, organisationId, name, startsOn, endsOn, timezone, country, venue, status, registrationOpen, placingPoints,
					createdAt, null, null, publicPath, null);
		}

	}

	/**
	 * Who's looking, and what they may do.
	 *
	 * @param role        admin (runs the workspace), staff (holds another seat in it), or player (only taking part)
	 * @param personId    their place in the event, if they have one
	 * @param coordinates the games they coordinate
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Viewer(String role, UUID personId, List<UUID> coordinates, List<UUID> represents) {
	}

	/**
	 * A group: a fellowship, a house, a company. Whether it has paid the group fee, and who its reps
	 * are, only for admins and its own reps.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Group(UUID id, String name, String colour, int position, String feePaidVia, Instant feePaidAt, List<Rep> reps) {

		Group(UUID id, String name, String colour, int position) {
			this(id, name, colour, position, null, null, null);
		}

		/** The group as everyone sees it: its name and colour. */
		Group plain() {
			return new Group(id, name, colour, position);
		}

	}

	/** Someone from a group who registers its people and enters them in games. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Rep(UUID userId, String name, String avatar) {
	}

	/** A sponsor of the event. {@code logoUrl} is an address on the API, when it has a logo. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Sponsor(UUID id, String name, boolean headline, String logoUrl) {
	}

	/** Someone taking part. {@code userId} only when they joined with their own account. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Person(UUID id, String name, UUID groupId, UUID userId, String avatar, String source, String feePaidVia, Instant feePaidAt) {

		/** The same person, without whether they've paid: for anyone but an admin, or the person themselves. */
		Person withoutFee() {
			return new Person(id, name, groupId, userId, avatar, source, null, null);
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Coordinator(UUID userId, String name, String avatar) {
	}

	/** Someone (or a pair, or a team) in a game. {@code pool} is the pool they were drawn into, for a game played in pools. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Entry(UUID id, String name, UUID groupId, List<UUID> personIds, int seed, String status, Integer pool) {
	}

	/**
	 * A game. {@code matchMinutes} and {@code locations} are its plan for the day, when the coordinator
	 * made one: how long each match or heat takes, and the places they're played at once. A game in
	 * pools has each pool's table in {@code pools}; its knockout is the matches with no pool.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Game(UUID id, String discipline, String name, String category, String entryKind, Integer teamSize, String format,
			String scoring, Integer bestOf, boolean drawsAllowed, boolean thirdPlace, Integer heatSize, Integer advancePerHeat,
			Integer poolSize, Integer advancePerPool, String location, Instant startsAt, Integer matchMinutes, List<String> locations,
			String status, int position, List<Coordinator> coordinators, List<Entry> entries, List<UUID> interested, List<Match> matches,
			List<Heat> heats, List<LeagueRow> table, List<Pool> pools, List<UUID> places) {

		/** As the public page shows it: entries by name, with nobody's place in the event and no staff. */
		Game forPublic() {
			var named = entries.stream().map(e -> new Entry(e.id(), e.name(), e.groupId(), List.of(), e.seed(), e.status(), e.pool())).toList();
			return new Game(id, discipline, name, category, entryKind, teamSize, format, scoring, bestOf, drawsAllowed, thirdPlace, heatSize,
					advancePerHeat, poolSize, advancePerPool, location, startsAt, matchMinutes, locations, status, position, List.of(), named,
					List.of(), matches, heats, table, pools, places);
		}

		Game withPlay(List<Match> matches, List<Heat> heats, List<LeagueRow> table, List<Pool> pools, List<UUID> places) {
			return new Game(id, discipline, name, category, entryKind, teamSize, format, scoring, bestOf, drawsAllowed, thirdPlace,
					heatSize, advancePerHeat, poolSize, advancePerPool, location, startsAt, matchMinutes, locations, status, position,
					coordinators, entries, interested, matches, heats, table, pools, places);
		}

	}

	/** One pool of a game played in pools: its table so far, best first. */
	public record Pool(int number, List<LeagueRow> table) {
	}

	public record SetScore(int home, int away) {
	}

	/**
	 * A knockout tie or league match. An empty side is a bye (when the other side has won it without
	 * playing) or someone still to come through.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Match(UUID id, int round, int slot, boolean thirdPlace, UUID homeEntryId, UUID awayEntryId, Integer homeScore,
			Integer awayScore, List<SetScore> sets, UUID winnerEntryId, String decidedBy, Integer homePenalties, Integer awayPenalties,
			Instant startsAt, String location, Instant recordedAt, Integer pool) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Lane(UUID entryId, int lane, Integer place, String mark) {
	}

	/** A heat or the final of a placings game: who's in it, and where they finished once it's run. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Heat(UUID id, String stage, int number, List<Lane> lanes, Instant startsAt, String location, Instant recordedAt) {
	}

	public record LeagueRow(UUID entryId, int played, int won, int drawn, int lost, int scored, int conceded, int points) {
	}

	/** A group in the overall table. */
	public record GroupRow(UUID groupId, int points, int gold, int silver, int bronze) {
	}

	/** The links only admins see: to join the event, to put its board on a screen, and each group's rep link. */
	public record Links(String joinUrl, String boardUrl, List<RepLink> reps) {
	}

	/** The link a group's rep signs up with. */
	public record RepLink(UUID groupId, String url) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Detail(Info event, Brand organisation, Viewer viewer, List<Group> groups, List<Person> people, List<Game> games,
			List<GroupRow> table, Links links, List<Announcement> announcements, List<Sponsor> sponsors) {
	}

	/** Something the organisers told everyone, newest first. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Announcement(UUID id, String body, Instant postedAt, String postedBy) {
	}

	/** One game on the board: how it stands, and who's up next. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record BoardGame(UUID id, String discipline, String name, String category, String status, String location, Instant startsAt,
			List<String> places, List<Next> next) {
	}

	/** A match or heat still to play, and when and where, if that's planned. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Next(String line, Instant startsAt, String location) {
	}

	/** A result just in, for the board. */
	public record Latest(String game, String summary, Instant at) {
	}

	/**
	 * The event on a big screen: the overall table, every game's standing and what's next, and the
	 * latest results. Names only, as people put them in: nothing private.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Board(String name, String timezone, Brand organisation, LocalDate startsOn, LocalDate endsOn, Venue venue, String status,
			List<Group> groups, List<GroupRow> table, List<BoardGame> games, List<Latest> latest, Instant at, Announcement announcement,
			List<Sponsor> sponsors) {
	}

	/**
	 * What a rep link is for, before it's used: the event and the group. {@code mine} says the viewer
	 * already represents it; {@code full} that it has as many reps as it can.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record RepPreview(UUID eventId, String eventName, Brand organisation, LocalDate startsOn, LocalDate endsOn, Group group,
			boolean registrationOpen, String status, Boolean mine, boolean full) {
	}

	/** One game, as someone deciding whether to join sees it. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record JoinGame(UUID id, String discipline, String name, String category, String entryKind, String status) {
	}

	/** What the signed-in player already has in the event they're joining. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	/** A claim link for someone added by name: the path to send them, shown once. */
	public record ClaimLink(String url) {
	}

	/**
	 * What a claim link is for, before it's used: the event, and the name the organisers typed.
	 * {@code mine} says the viewer is already that person; {@code claimed} that someone already has.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ClaimPreview(UUID eventId, String eventName, Brand organisation, LocalDate startsOn, LocalDate endsOn, String personName,
			Group group, int games, boolean claimed, Boolean mine) {
	}

	/**
	 * A finished event someone took part in with their account, for their profile: their group, how
	 * it finished overall, and every game they played with where they placed (null: no place).
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Result(UUID eventId, String name, String organisationName, LocalDate startsOn, LocalDate endsOn, Group group,
			Integer groupPlace, List<GamePlace> games) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record GamePlace(String name, String category, String discipline, Integer place) {
	}

	public record Mine(UUID personId, UUID groupId, List<UUID> gameIds) {
	}

	/** The event behind a join link, before joining. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record JoinPreview(UUID id, String name, Brand organisation, LocalDate startsOn, LocalDate endsOn, Venue venue, String status,
			boolean registrationOpen, List<Group> groups, List<JoinGame> games, Mine mine) {
	}

}
