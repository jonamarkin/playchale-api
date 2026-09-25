package com.playchale.api.competitions.internal.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.playchale.api.competitions.api.CompetitionEvents;
import com.playchale.api.competitions.api.CompetitionEvents.LeagueInfo;
import com.playchale.api.competitions.internal.domain.Competition;
import com.playchale.api.competitions.internal.domain.CompetitionDetails;
import com.playchale.api.competitions.internal.domain.JoinRequest;
import com.playchale.api.competitions.internal.domain.RoundRobin;
import com.playchale.api.competitions.internal.domain.Team;
import com.playchale.api.competitions.internal.repository.CompetitionRepository;
import com.playchale.api.competitions.internal.repository.JoinRequestRepository;
import com.playchale.api.competitions.internal.repository.TeamRepository;
import com.playchale.api.games.api.Fixtures;
import com.playchale.api.market.Market;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.venues.api.PitchBookings;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Competitions: the organiser sets up the league and its teams and draws the fixtures; captains run
 * their squads. Every change locks the competition, and the database holds each player to one team
 * per league whatever happens at once.
 */
@Service
public class CompetitionService {

	private static final int LIST_LIMIT = 50;

	private static final String NOT_FOUND = "That league doesn’t exist any more.";

	private final CompetitionRepository competitions;

	private final TeamRepository teams;

	private final JoinRequestRepository requests;

	private final CompetitionViews views;

	private final Fixtures fixtures;

	private final UserDirectory users;

	private final PitchBookings venues;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	CompetitionService(CompetitionRepository competitions, TeamRepository teams, JoinRequestRepository requests, CompetitionViews views,
			Fixtures fixtures, UserDirectory users, PitchBookings venues, ApplicationEventPublisher events, Clock clock) {
		this.competitions = competitions;
		this.teams = teams;
		this.requests = requests;
		this.views = views;
		this.fixtures = fixtures;
		this.users = users;
		this.venues = venues;
		this.events = events;
		this.clock = clock;
	}

	/** competitions.list: leagues anyone can look at (drawn, not drafts), newest first. */
	@Transactional(readOnly = true)
	public List<CompetitionResponse> list(UUID viewer) {
		return views.of(competitions.findByStatusNotOrderByCreatedAtDesc(Competition.DRAFT, Limit.of(LIST_LIMIT)), viewer);
	}

	/** competitions.mine: leagues the player organises or plays in. */
	@Transactional(readOnly = true)
	public List<CompetitionResponse> mine(UUID me) {
		return views.of(competitions.involving(me, Limit.of(LIST_LIMIT)), me);
	}

	/** competitions.get */
	@Transactional(readOnly = true)
	public Optional<CompetitionResponse> get(UUID id, UUID viewer) {
		return competitions.findById(id).map(c -> views.of(c, viewer));
	}

	/** competitions.create: a draft until the fixtures are drawn. */
	@Transactional
	public CompetitionResponse create(CompetitionDetails details, UUID me) {
		var competition = new Competition(details, me, clock.instant());
		if ("listed".equals(details.venueKind())) {
			var venue = venues.findVenue(details.venueId() == null ? new UUID(0, 0) : details.venueId())
				.orElseThrow(() -> BusinessException.invalid("That venue could not be found."));
			competition.playAt(venue.id(), venue.name(), venue.area());
		}
		else {
			competition.playAt(details.venueName(), details.venueArea());
		}
		return views.of(competitions.save(competition), me);
	}

	/**
	 * competitions.addTeam: organiser only, before the fixtures are drawn. A named captain plays in the
	 * squad; without one, the organiser runs the team without playing for it.
	 */
	@Transactional
	public CompetitionResponse addTeam(UUID id, String name, UUID captainId, Collection<UUID> playerIds, UUID me) {
		var competition = organised(id, me);
		var existing = teams.findByCompetitionIdOrderByCreatedAt(id);
		var trimmed = name == null ? "" : name.strip();
		if (existing.stream().anyMatch(t -> t.getName().equalsIgnoreCase(trimmed))) {
			throw BusinessException.conflict("%s is already in this league.".formatted(trimmed));
		}
		requireNotDrawn(id, "The fixtures are drawn. Add teams before drawing them, or start a new league.");
		var captain = captainId == null ? me : captainId;
		if (captainId != null) {
			if (users.find(captainId).isEmpty()) {
				throw BusinessException.invalid("Pick a captain from your players.");
			}
			if (teams.isPlaying(id, captainId)) {
				throw BusinessException.conflict("%s is already in another team in this league.".formatted(firstName(captainId)));
			}
		}
		var now = clock.instant();
		var tint = Competition.TEAM_TINTS.get(existing.size() % Competition.TEAM_TINTS.size());
		var team = new Team(id, trimmed, captain, captainId != null, tint, now);
		var wanted = playerIds == null ? List.<UUID>of() : playerIds;
		var real = users.findAll(wanted).keySet();
		wanted.stream().distinct().filter(real::contains).filter(p -> !teams.isPlaying(id, p)).forEach(p -> team.add(p, now));
		saveSquad(team);
		return views.of(competition, me);
	}

	/** competitions.removeTeam: organiser only, before the fixtures are drawn. */
	@Transactional
	public CompetitionResponse removeTeam(UUID id, UUID teamId, UUID me) {
		var competition = organised(id, me);
		requireNotDrawn(id, "The fixtures are drawn, so teams can’t be dropped.");
		teams.delete(team(id, teamId));
		return views.of(competition, me);
	}

	/** competitions.generateFixtures: organiser only. Everyone plays everyone once, a round a week, fixtures back to back on the day. */
	@Transactional
	public CompetitionResponse generateFixtures(UUID id, UUID me) {
		var competition = organised(id, me);
		requireNotDrawn(id, "The fixtures are already drawn.");
		var squads = teams.findByCompetitionIdOrderByCreatedAt(id);
		if (squads.size() < 3) {
			throw BusinessException.invalid("A league needs at least three teams.");
		}
		var byId = squads.stream().collect(Collectors.toMap(Team::getId, t -> t));
		var rounds = RoundRobin.rounds(squads.stream().map(Team::getId).toList());
		var firstDay = competition.getStartsAt().atZone(Market.get(Market.DEFAULT).zone());
		var specs = new ArrayList<Fixtures.FixtureSpec>();
		for (int round = 0; round < rounds.size(); round++) {
			var pairs = rounds.get(round);
			for (int i = 0; i < pairs.size(); i++) {
				var home = byId.get(pairs.get(i).home());
				var away = byId.get(pairs.get(i).away());
				var startsAt = firstDay.plusWeeks(round).plusMinutes((long) i * competition.getDurationMinutes()).toInstant();
				specs.add(new Fixtures.FixtureSpec(id, round + 1, home.getId(), away.getId(), "%s vs %s".formatted(home.getName(), away.getName()),
						competition.getSport(), competition.getFormat(), startsAt, competition.getDurationMinutes(), competition.getVenueKind(),
						competition.getVenueId(), competition.getVenueName(), competition.getVenueArea(), competition.getOrganiserId(),
						squadOf(home, away)));
			}
		}
		fixtures.create(specs);
		competition.start();
		var players = squads.stream().flatMap(t -> t.playerIds().stream()).distinct().filter(p -> !p.equals(me)).toList();
		events.publishEvent(new CompetitionEvents.FixturesDrawn(info(competition), me, players, rounds.size(), competition.getStartsAt()));
		return views.of(competition, me);
	}

	/** competitions.addPlayers: captain or organiser. Upcoming fixtures pick them up. */
	@Transactional
	public CompetitionResponse addPlayers(UUID id, UUID teamId, Collection<UUID> userIds, UUID me) {
		var competition = locked(id);
		var team = runBy(competition, teamId, me);
		var real = users.findAll(userIds).keySet();
		var adding = userIds.stream().distinct().filter(real::contains).filter(p -> !teams.isPlaying(id, p)).toList();
		if (adding.isEmpty()) {
			throw BusinessException.conflict("Those players are already in a team in this league.");
		}
		var now = clock.instant();
		adding.forEach(p -> team.add(p, now));
		saveSquad(team);
		syncFixtures(competition, team);
		var others = adding.stream().filter(p -> !p.equals(me)).toList();
		if (!others.isEmpty()) {
			events.publishEvent(new CompetitionEvents.AddedToSquad(info(competition), team.getName(), me, others));
		}
		return views.of(competition, me);
	}

	/** competitions.removePlayer: captain or organiser. Captains can't drop themselves. */
	@Transactional
	public CompetitionResponse removePlayer(UUID id, UUID teamId, UUID userId, UUID me) {
		var competition = locked(id);
		var team = runBy(competition, teamId, me);
		team.remove(userId);
		teams.saveAndFlush(team);
		syncFixtures(competition, team);
		return views.of(competition, me);
	}

	/** competitions.requestJoin: ask a captain for a place. */
	@Transactional
	public CompetitionResponse requestJoin(UUID id, UUID teamId, UUID me) {
		var competition = locked(id);
		var team = team(id, teamId);
		if (teams.isPlaying(id, me)) {
			throw BusinessException.conflict("You’re already playing in this league.");
		}
		if (requests.existsByTeamIdAndUserIdAndStatus(teamId, me, JoinRequest.PENDING)) {
			throw BusinessException.conflict("%s already has your request.".formatted(team.getName()));
		}
		requests.save(new JoinRequest(id, teamId, me, clock.instant()));
		events.publishEvent(new CompetitionEvents.SquadRequested(info(competition), team.getName(), team.getCaptainId(), me));
		return views.of(competition, me);
	}

	/** competitions.answerRequest: captain or organiser says yes or no. */
	@Transactional
	public CompetitionResponse answerRequest(UUID id, UUID requestId, boolean accept, UUID me) {
		var competition = locked(id);
		var request = requests.findById(requestId).filter(r -> r.getCompetitionId().equals(id) && r.isPending())
			.orElseThrow(() -> BusinessException.notFound("That request has already been answered."));
		var team = runBy(competition, request.getTeamId(), me);
		request.answer(accept, clock.instant());
		if (accept) {
			if (teams.isPlaying(id, request.getUserId())) {
				throw BusinessException.conflict("They’ve joined another team in the meantime.");
			}
			team.add(request.getUserId(), clock.instant());
			saveSquad(team);
			syncFixtures(competition, team);
		}
		events.publishEvent(new CompetitionEvents.SquadAnswered(info(competition), team.getName(), me, request.getUserId(), accept));
		return views.of(competition, me);
	}

	/** competitions.joinWithToken: from the squad link a captain shared. */
	@Transactional
	public CompetitionResponse joinWithToken(UUID id, String token, UUID me) {
		var competition = locked(id);
		var team = teams.findByCompetitionIdAndJoinToken(id, token == null ? "" : token)
			.orElseThrow(() -> BusinessException.notFound("That squad link doesn’t work any more. Ask the captain for a new one."));
		if (team.has(me)) {
			throw BusinessException.conflict("You’re already in %s.".formatted(team.getName()));
		}
		if (teams.isPlaying(id, me)) {
			throw BusinessException.conflict("You’re already playing for another team in this league.");
		}
		team.add(me, clock.instant());
		saveSquad(team);
		syncFixtures(competition, team);
		events.publishEvent(new CompetitionEvents.JoinedByLink(info(competition), team.getName(), team.getCaptainId(), me,
				team.playerIds().size()));
		return views.of(competition, me);
	}

	/** Upcoming fixtures involving the team get its new squad (with their opponents'). */
	private void syncFixtures(Competition competition, Team team) {
		var squads = teams.findByCompetitionIdOrderByCreatedAt(competition.getId()).stream()
			.collect(Collectors.toMap(Team::getId, t -> t));
		for (var f : fixtures.of(competition.getId())) {
			if (!f.homeTeamId().equals(team.getId()) && !f.awayTeamId().equals(team.getId())) {
				continue;
			}
			var home = squads.get(f.homeTeamId());
			var away = squads.get(f.awayTeamId());
			if (home != null && away != null) {
				fixtures.syncSquad(f.gameId(), squadOf(home, away));
			}
		}
	}

	/** Writes a squad now, so if the same player was added somewhere else a moment ago it's a clear refusal. */
	private void saveSquad(Team team) {
		try {
			teams.saveAndFlush(team);
		}
		catch (DataIntegrityViolationException e) {
			throw BusinessException.conflict("Someone in that squad is already in another team in this league.");
		}
	}

	private static List<UUID> squadOf(Team home, Team away) {
		var squad = new LinkedHashSet<UUID>(home.playerIds());
		squad.addAll(away.playerIds());
		return List.copyOf(squad);
	}

	private void requireNotDrawn(UUID id, String message) {
		if (!fixtures.of(id).isEmpty()) {
			throw BusinessException.conflict(message);
		}
	}

	private Competition organised(UUID id, UUID me) {
		var competition = locked(id);
		if (!competition.isOrganisedBy(me)) {
			throw BusinessException.conflict("Only the organiser can change this league.");
		}
		return competition;
	}

	private Team runBy(Competition competition, UUID teamId, UUID me) {
		var team = team(competition.getId(), teamId);
		if (!team.isRunBy(me, competition)) {
			throw BusinessException.conflict("Only %s or the organiser can change this squad.".formatted(firstName(team.getCaptainId())));
		}
		return team;
	}

	private Team team(UUID competitionId, UUID teamId) {
		return teams.findById(teamId).filter(t -> t.getCompetitionId().equals(competitionId))
			.orElseThrow(() -> BusinessException.notFound("That team isn’t in this league."));
	}

	private Competition locked(UUID id) {
		return competitions.lockById(id).orElseThrow(() -> BusinessException.notFound(NOT_FOUND));
	}

	private String firstName(UUID userId) {
		return users.find(userId).map(u -> u.name().split(" ")[0]).filter(n -> !n.isBlank()).orElse("the captain");
	}

	private static LeagueInfo info(Competition c) {
		return new LeagueInfo(c.getId(), c.getName());
	}

}
