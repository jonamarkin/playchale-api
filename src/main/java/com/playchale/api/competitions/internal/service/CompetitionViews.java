package com.playchale.api.competitions.internal.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import com.playchale.api.competitions.internal.domain.Competition;
import com.playchale.api.competitions.internal.domain.Entry;
import com.playchale.api.competitions.internal.domain.EntryId;
import com.playchale.api.competitions.internal.repository.EntryRepository;
import com.playchale.api.games.api.FixtureTeams;
import com.playchale.api.games.api.Fixtures;
import com.playchale.api.games.api.GameResponse;
import com.playchale.api.teams.api.JoinRequestCard;
import com.playchale.api.teams.api.TeamCard;
import com.playchale.api.teams.api.TeamDirectory;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.venues.api.PitchBookings;
import com.playchale.api.users.api.UserSummary;
import org.springframework.stereotype.Component;

/** Builds what a competition page shows, including the league table worked out from fixture results. */
@Component
class CompetitionViews {

	private final EntryRepository entries;

	private final TeamDirectory directory;

	private final Fixtures fixtures;

	private final UserDirectory users;

	private final PitchBookings venues;

	CompetitionViews(EntryRepository entries, TeamDirectory directory, Fixtures fixtures, UserDirectory users, PitchBookings venues) {
		this.entries = entries;
		this.directory = directory;
		this.fixtures = fixtures;
		this.users = users;
		this.venues = venues;
	}

	List<CompetitionResponse> of(Collection<Competition> competitions, UUID viewer) {
		return competitions.stream().map(c -> of(c, viewer)).toList();
	}

	CompetitionResponse of(Competition c, UUID viewer) {
		var squads = entries.inCompetition(c.getId());
		var cards = directory.findAll(squads.stream().map(Entry::getTeamId).toList());
		var pending = directory.pendingRequests(cards.keySet());
		var people = users.findAll(Stream.of(Stream.of(c.getOrganiserId()), squads.stream().flatMap(e -> e.playerIds().stream()),
				cards.values().stream().map(TeamCard::captainId), pending.stream().map(JoinRequestCard::userId)).flatMap(s -> s).distinct().toList());

		var teamViews = squads.stream().filter(e -> cards.containsKey(e.getTeamId())).map(e -> {
			var t = cards.get(e.getTeamId());
			return new CompetitionResponse.TeamView(t.id(), c.getId(), t.name(), t.captainId(), e.playerIds(), t.tint(), token(t, c, viewer),
					t.createdAt(), e.playerIds().stream().map(people::get).filter(u -> u != null).map(u -> u.as(viewer)).toList(),
					shown(people.get(t.captainId()), viewer), e.isInvited() ? Entry.INVITED : null);
		}).toList();

		var summaries = fixtures.of(c.getId());
		var views = fixtures.views(c.getId(), viewer);
		var rounds = summaries.stream().mapToInt(Fixtures.FixtureSummary::round).max().orElse(0);
		var requestViews = pending.stream().map(r -> new CompetitionResponse.RequestView(r.id(), c.getId(), r.teamId(), r.userId(), r.status(),
				r.createdAt(), shown(people.get(r.userId()), viewer))).toList();

		// A partner venue's own map link, so a pin its owner adds later shows here too.
		var mapUrl = c.getVenueId() != null ? venues.mapLinks(List.of(c.getVenueId())).get(c.getVenueId()) : c.getMapUrl();
		var venue = new GameResponse.VenueRef(c.getVenueKind(), c.getVenueId(), c.getVenueName(), c.getVenueArea(), null, null, mapUrl);
		return new CompetitionResponse(c.getId(), c.getName(), c.getSport(), c.getFormat(), c.getOrganiserId(), venue, c.getStartsAt(),
				c.getDurationMinutes(), c.getStatus(), new CompetitionResponse.Points(c.getPointsWin(), c.getPointsDraw(), c.getPointsLoss()),
				c.getCreatedAt(), shown(people.get(c.getOrganiserId()), viewer), teamViews, table(c, squads, cards, summaries, viewer), views, rounds,
				requestViews);
	}

	/**
	 * The league table from the fixtures played so far: points by the league's rules, then goal (or
	 * point, or set) difference, then scored, then name.
	 */
	private List<CompetitionResponse.TableRow> table(Competition c, List<Entry> squads, Map<UUID, TeamCard> cards,
			List<Fixtures.FixtureSummary> summaries, UUID viewer) {
		var rows = new LinkedHashMap<UUID, Row>();
		squads.stream().filter(e -> !e.isInvited() && cards.containsKey(e.getTeamId()))
			.forEach(e -> rows.put(e.getTeamId(), new Row(card(cards.get(e.getTeamId()), e, c, viewer))));
		summaries.stream().filter(Fixtures.FixtureSummary::played).sorted(Comparator.comparing(Fixtures.FixtureSummary::startsAt)).forEach(f -> {
			var home = rows.get(f.homeTeamId());
			var away = rows.get(f.awayTeamId());
			if (home != null) {
				home.record(c, f.homeScore(), f.awayScore());
			}
			if (away != null) {
				away.record(c, f.awayScore(), f.homeScore());
			}
		});
		return rows.values().stream()
			.sorted(Comparator.comparingInt((Row r) -> -r.points)
				.thenComparingInt(r -> -(r.scored - r.conceded))
				.thenComparingInt(r -> -r.scored)
				.thenComparing(r -> r.team.name()))
			.map(Row::toResponse)
			.toList();
	}

	private static final class Row {

		private final FixtureTeams.TeamCard team;

		private int played;

		private int won;

		private int drawn;

		private int lost;

		private int scored;

		private int conceded;

		private int points;

		private final List<String> form = new ArrayList<>();

		Row(FixtureTeams.TeamCard team) {
			this.team = team;
		}

		void record(Competition c, int forScore, int againstScore) {
			var outcome = forScore > againstScore ? "W" : forScore < againstScore ? "L" : "D";
			played++;
			scored += forScore;
			conceded += againstScore;
			switch (outcome) {
				case "W" -> won++;
				case "D" -> drawn++;
				default -> lost++;
			}
			points += c.pointsFor(outcome);
			form.add(outcome);
		}

		CompetitionResponse.TableRow toResponse() {
			return new CompetitionResponse.TableRow(team, played, won, drawn, lost, scored, conceded, scored - conceded, points,
					form.subList(Math.max(0, form.size() - 5), form.size()));
		}

	}

	/** A team in a league as the web app's Team type: its squad here. Its link only for the captain and the organiser. */
	FixtureTeams.TeamCard card(TeamCard t, Entry e, Competition c, UUID viewer) {
		return new FixtureTeams.TeamCard(t.id(), c.getId(), t.name(), t.captainId(), e.playerIds(), t.tint(), token(t, c, viewer), t.createdAt());
	}

	private String token(TeamCard t, Competition c, UUID viewer) {
		return viewer != null && (t.captainId().equals(viewer) || c.isOrganisedBy(viewer)) ? directory.joinToken(t.id()) : "";
	}

	private static UserSummary shown(UserSummary user, UUID viewer) {
		return user == null ? null : user.as(viewer);
	}

	/** Teams in a league as fixtures show them: no squad links. */
	Map<UUID, FixtureTeams.TeamCard> cards(UUID competitionId, Collection<UUID> teamIds) {
		var cards = directory.findAll(teamIds);
		var out = new LinkedHashMap<UUID, FixtureTeams.TeamCard>();
		for (var teamId : teamIds) {
			var t = cards.get(teamId);
			if (t == null) {
				continue;
			}
			var squad = entries.findById(new EntryId(competitionId, teamId)).map(Entry::playerIds).orElse(List.of());
			out.put(teamId, new FixtureTeams.TeamCard(t.id(), competitionId, t.name(), t.captainId(), squad, t.tint(), "", t.createdAt()));
		}
		return out;
	}

}
