package com.playchale.api.competitions.internal.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.playchale.api.competitions.internal.domain.Competition;
import com.playchale.api.competitions.internal.domain.Entry;
import com.playchale.api.competitions.internal.domain.EntryId;
import com.playchale.api.competitions.internal.domain.Knockout;
import com.playchale.api.competitions.internal.repository.EntryRepository;
import com.playchale.api.games.api.FixtureTeams;
import com.playchale.api.games.api.Fixtures;
import com.playchale.api.games.api.GameResponse;
import com.playchale.api.organisations.api.OrganisationAccess;
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

	private final OrganisationAccess organisations;

	private final PublicRosters rosters;

	private final RosterScorers rosterScorers;

	CompetitionViews(EntryRepository entries, TeamDirectory directory, Fixtures fixtures, UserDirectory users, PitchBookings venues,
			OrganisationAccess organisations, PublicRosters rosters, RosterScorers rosterScorers) {
		this.entries = entries;
		this.directory = directory;
		this.fixtures = fixtures;
		this.users = users;
		this.venues = venues;
		this.organisations = organisations;
		this.rosters = rosters;
		this.rosterScorers = rosterScorers;
	}

	List<CompetitionResponse> of(Collection<Competition> competitions, UUID viewer) {
		return competitions.stream().map(c -> of(c, viewer)).toList();
	}

	/**
	 * A company's player as the chart shows them: their account if they have claimed their place,
	 * otherwise just the name their company put forward. They have no profile to open and no stats of
	 * their own, which is the honest picture — a staff list is not a set of PlayChale accounts.
	 */
	private static UserSummary namedPlayer(RosterScorers.RosterScorer scorer, UserSummary claimed, UUID viewer) {
		if (claimed != null) {
			return claimed.as(viewer);
		}
		return new UserSummary(scorer.rosterMemberId(), null, scorer.displayName(), null, null, null, null, null,
				List.of(), Map.of(), null, true, null, null, null, null, null, null);
	}

	CompetitionResponse of(Competition c, UUID viewer) {
		var squads = entries.inCompetition(c.getId());
		var cards = directory.findAll(squads.stream().map(Entry::getTeamId).toList());
		var pending = directory.pendingRequests(cards.keySet());
		// Scorers are looked up with everyone else: someone who has left a squad still scored their goals.
		var scored = fixtures.scorers(c.getId(), c.getSport());
		// A company league's chart comes from the match sheets instead (see RosterScorers).
		var fromSheets = c.getOrganisationId() == null ? List.<RosterScorers.RosterScorer>of() : rosterScorers.of(c.getId());
		var people = users.findAll(Stream.of(Stream.of(c.getOrganiserId()), squads.stream().flatMap(e -> e.playerIds().stream()),
				cards.values().stream().map(TeamCard::captainId), pending.stream().map(JoinRequestCard::userId),
				scored.stream().map(Fixtures.Scorer::userId), fromSheets.stream().map(RosterScorers.RosterScorer::userId).filter(Objects::nonNull),
				c.getOrganisers().stream()).flatMap(s -> s).distinct().toList());

		// A company enters as itself, so who plays for it is its approved roster rather than a squad.
		var rostersByTeam = c.getOrganisationId() == null ? Map.<UUID, List<PublicRosters.PublicPlayer>>of() : rosters.of(c.getId());
		var teamViews = squads.stream().filter(e -> cards.containsKey(e.getTeamId())).map(e -> {
			var t = cards.get(e.getTeamId());
			var roster = rostersByTeam.getOrDefault(t.id(), List.of()).stream()
				.map(p -> new CompetitionResponse.RosterName(p.displayName(), p.userId()))
				.toList();
			return new CompetitionResponse.TeamView(t.id(), c.getId(), t.name(), t.captainId(), e.playerIds(), t.tint(), token(t, c, viewer),
					t.createdAt(), e.playerIds().stream().map(people::get).filter(u -> u != null).map(u -> u.as(viewer)).toList(),
					shown(people.get(t.captainId()), viewer), e.isInvited() ? Entry.INVITED : null, roster);
		}).toList();

		// Two players level on goals, assists and games are separated by name, so the chart reads the
		// same every time rather than in whatever order the rows came back.
		var scorers = c.getOrganisationId() != null
			? fromSheets.stream()
				.map(r -> new CompetitionResponse.ScorerView(namedPlayer(r, people.get(r.userId()), viewer), r.teamId(),
						cards.containsKey(r.teamId()) ? cards.get(r.teamId()).name() : null, r.goals(), r.assists(), 0, r.games()))
				.toList()
			: scored.stream()
			.map(s -> new CompetitionResponse.ScorerView(shown(people.get(s.userId()), viewer), s.teamId(),
					s.teamId() == null ? null : cards.containsKey(s.teamId()) ? cards.get(s.teamId()).name() : null,
					s.goals(), s.assists(), s.points(), s.games()))
			.filter(s -> s.player() != null)
			.sorted(Comparator.comparingInt((CompetitionResponse.ScorerView v) -> v.goals() + v.points()).reversed()
				.thenComparing(Comparator.comparingInt(CompetitionResponse.ScorerView::assists).reversed())
				.thenComparing(CompetitionResponse.ScorerView::games)
				.thenComparing(v -> v.player().name(), String.CASE_INSENSITIVE_ORDER))
			.toList();

		var organisers = c.getOrganisers().stream().map(people::get).filter(u -> u != null).map(u -> u.as(viewer))
			.sorted(Comparator.comparing(UserSummary::name)).toList();

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
				requestViews, c.getPlayerLists(), c.getCountry(), c.getCurrency(), c.getTimezone(), scorers, organisers, c.getStructure(),
				bracket(c, squads, cards, summaries, viewer), c.getOrganisationId(), c.isCorporate() ? c.getScheduleStatus() : null,
				c.isCorporate() ? ((c.isOrganisedBy(viewer) || organisations.isAdmin(c.getOrganisationId(), viewer))
					? List.of("manage", "schedule", "eligibility", "finance", "reports") : List.of()) : null,
				brand(c));
	}

	private CompetitionResponse.Brand brand(Competition competition) {
		if (competition.getOrganisationId() == null) return null;
		var brand = organisations.brand(competition.getOrganisationId());
		return brand == null ? null : new CompetitionResponse.Brand(brand.id(), brand.name(), brand.primaryColour(), brand.logoUrl());
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
	/**
	 * The bracket as it stands: every round the cup will have, each with the ties drawn so far. A
	 * round nobody has reached yet is still listed, with how many ties it will hold, so the shape of
	 * the cup is there from the first whistle.
	 */
	private List<CompetitionResponse.BracketRound> bracket(Competition c, List<Entry> squads, Map<UUID, TeamCard> cards,
			List<Fixtures.FixtureSummary> summaries, UUID viewer) {
		if (!c.isKnockout()) {
			return List.of();
		}
		var shape = Knockout.shape(squads.size());
		var byes = Knockout.firstRound(squads.stream().map(Entry::getTeamId).toList()).byes();
		var byRound = summaries.stream().collect(Collectors.groupingBy(Fixtures.FixtureSummary::round));
		var rounds = new ArrayList<CompetitionResponse.BracketRound>();
		for (int i = 0; i < shape.size(); i++) {
			var round = i + 1;
			var ties = new ArrayList<>(byRound.getOrDefault(round, List.of()).stream()
				.map(f -> new CompetitionResponse.BracketTie(f.gameId(), f.slot() == null ? 0 : f.slot(),
						card(cards.get(f.homeTeamId()), squads, c, viewer), card(cards.get(f.awayTeamId()), squads, c, viewer),
						f.homeScore(), f.awayScore(), f.homePenalties(), f.awayPenalties(), f.winner(), f.startsAt(), f.status(), false))
				.toList());
			// A bye is shown where it sits: that team is through without playing.
			if (round == 1) {
				byes.forEach((slot, team) -> ties.add(new CompetitionResponse.BracketTie(null, slot,
						card(cards.get(team), squads, c, viewer), null, null, null, null, null, team, null, null, true)));
			}
			ties.sort(Comparator.comparingInt(CompetitionResponse.BracketTie::slot));
			rounds.add(new CompetitionResponse.BracketRound(round, Knockout.name(shape.get(i)), shape.get(i), List.copyOf(ties)));
		}
		return List.copyOf(rounds);
	}

	/** A team as the bracket shows it, or nothing where a tie is still waiting on a winner. */
	private FixtureTeams.TeamCard card(TeamCard team, List<Entry> squads, Competition c, UUID viewer) {
		if (team == null) {
			return null;
		}
		var entry = squads.stream().filter(e -> e.getTeamId().equals(team.id())).findFirst().orElse(null);
		return entry == null ? null : card(team, entry, c, viewer);
	}

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
