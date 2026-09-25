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
import com.playchale.api.competitions.internal.domain.JoinRequest;
import com.playchale.api.competitions.internal.domain.Team;
import com.playchale.api.competitions.internal.repository.JoinRequestRepository;
import com.playchale.api.competitions.internal.repository.TeamRepository;
import com.playchale.api.games.api.FixtureTeams.TeamCard;
import com.playchale.api.games.api.Fixtures;
import com.playchale.api.games.api.GameResponse;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.users.api.UserSummary;
import org.springframework.stereotype.Component;

/** Builds what a competition page shows, including the league table worked out from fixture results. */
@Component
class CompetitionViews {

	private final TeamRepository teams;

	private final JoinRequestRepository requests;

	private final Fixtures fixtures;

	private final UserDirectory users;

	CompetitionViews(TeamRepository teams, JoinRequestRepository requests, Fixtures fixtures, UserDirectory users) {
		this.teams = teams;
		this.requests = requests;
		this.fixtures = fixtures;
		this.users = users;
	}

	List<CompetitionResponse> of(Collection<Competition> competitions, UUID viewer) {
		return competitions.stream().map(c -> of(c, viewer)).toList();
	}

	CompetitionResponse of(Competition c, UUID viewer) {
		var squads = teams.findByCompetitionIdOrderByCreatedAt(c.getId());
		var pending = requests.findByCompetitionIdAndStatusOrderByCreatedAt(c.getId(), JoinRequest.PENDING);
		var people = users.findAll(Stream.of(Stream.of(c.getOrganiserId()), squads.stream().flatMap(t -> t.playerIds().stream()),
				squads.stream().map(Team::getCaptainId), pending.stream().map(JoinRequest::getUserId)).flatMap(s -> s).distinct().toList());

		var teamViews = squads.stream().map(t -> new CompetitionResponse.TeamView(t.getId(), t.getCompetitionId(), t.getName(), t.getCaptainId(),
				t.playerIds(), t.getTint(), token(t, c, viewer), t.getCreatedAt(),
				t.playerIds().stream().map(people::get).filter(u -> u != null).map(u -> u.as(viewer)).toList(),
				shown(people.get(t.getCaptainId()), viewer))).toList();

		var summaries = fixtures.of(c.getId());
		var views = fixtures.views(c.getId(), viewer);
		var rounds = summaries.stream().mapToInt(Fixtures.FixtureSummary::round).max().orElse(0);
		var requestViews = pending.stream().map(r -> new CompetitionResponse.RequestView(r.getId(), r.getCompetitionId(), r.getTeamId(),
				r.getUserId(), r.getStatus(), r.getCreatedAt(), shown(people.get(r.getUserId()), viewer))).toList();

		var venue = new GameResponse.VenueRef(c.getVenueKind(), c.getVenueId(), c.getVenueName(), c.getVenueArea(), null, null);
		return new CompetitionResponse(c.getId(), c.getName(), c.getSport(), c.getFormat(), c.getOrganiserId(), venue, c.getStartsAt(),
				c.getDurationMinutes(), c.getStatus(), new CompetitionResponse.Points(c.getPointsWin(), c.getPointsDraw(), c.getPointsLoss()),
				c.getCreatedAt(), shown(people.get(c.getOrganiserId()), viewer), teamViews, table(c, squads, summaries, viewer), views, rounds,
				requestViews);
	}

	/**
	 * The league table from the fixtures played so far: points by the league's rules, then goal (or
	 * point, or set) difference, then scored, then name.
	 */
	private static List<CompetitionResponse.TableRow> table(Competition c, List<Team> squads, List<Fixtures.FixtureSummary> summaries,
			UUID viewer) {
		var rows = new LinkedHashMap<UUID, Row>();
		squads.forEach(t -> rows.put(t.getId(), new Row(card(t, c, viewer))));
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

		private final TeamCard team;

		private int played;

		private int won;

		private int drawn;

		private int lost;

		private int scored;

		private int conceded;

		private int points;

		private final List<String> form = new ArrayList<>();

		Row(TeamCard team) {
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

	/** A team as the web app's Team type. Its squad link only for the captain and the organiser. */
	static TeamCard card(Team t, Competition c, UUID viewer) {
		return new TeamCard(t.getId(), t.getCompetitionId(), t.getName(), t.getCaptainId(), t.playerIds(), t.getTint(), token(t, c, viewer),
				t.getCreatedAt());
	}

	private static String token(Team t, Competition c, UUID viewer) {
		return viewer != null && t.isRunBy(viewer, c) ? t.getJoinToken() : "";
	}

	private static UserSummary shown(UserSummary user, UUID viewer) {
		return user == null ? null : user.as(viewer);
	}

	static Map<UUID, TeamCard> cards(Collection<Team> teams) {
		var cards = new LinkedHashMap<UUID, TeamCard>();
		teams.forEach(t -> cards.put(t.getId(), new TeamCard(t.getId(), t.getCompetitionId(), t.getName(), t.getCaptainId(), t.playerIds(),
				t.getTint(), "", t.getCreatedAt())));
		return cards;
	}

}
