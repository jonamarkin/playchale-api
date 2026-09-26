package com.playchale.api.competitions.internal.service;

import java.time.Clock;
import java.time.Instant;
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
import com.playchale.api.competitions.internal.domain.Entry;
import com.playchale.api.competitions.internal.domain.EntryId;
import com.playchale.api.competitions.internal.domain.RoundRobin;
import com.playchale.api.competitions.internal.repository.CompetitionRepository;
import com.playchale.api.competitions.internal.repository.EntryRepository;
import com.playchale.api.games.api.Fixtures;
import com.playchale.api.market.Market;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.teams.api.TeamCard;
import com.playchale.api.teams.api.TeamDirectory;
import com.playchale.api.teams.api.TeamEvents;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.venues.api.PitchBookings;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Competitions: the organiser sets up the league, enters teams and draws the fixtures; captains run
 * their squads. Teams are the teams module's (they stand on their own); a league keeps each team's
 * squad for it. Every change locks the competition, and the database holds each player to one team
 * per league whatever happens at once.
 */
@Service
public class CompetitionService {

	private static final int LIST_LIMIT = 50;

	private static final String NOT_FOUND = "That league doesn’t exist any more.";

	private final CompetitionRepository competitions;

	private final EntryRepository entries;

	private final TeamDirectory directory;

	private final CompetitionViews views;

	private final Fixtures fixtures;

	private final UserDirectory users;

	private final PitchBookings venues;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	CompetitionService(CompetitionRepository competitions, EntryRepository entries, TeamDirectory directory, CompetitionViews views,
			Fixtures fixtures, UserDirectory users, PitchBookings venues, ApplicationEventPublisher events, Clock clock) {
		this.competitions = competitions;
		this.entries = entries;
		this.directory = directory;
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
			competition.playAt(details.venueName(), details.venueArea(), details.venueMapUrl());
		}
		return views.of(competitions.save(competition), me);
	}

	/**
	 * competitions.addTeam: organiser only, before the fixtures are drawn. Either an existing team
	 * ({@code teamId}): straight in if the organiser captains it, otherwise its captain is invited and
	 * accepts. Or a new team the organiser sets up ({@code name}), e.g. a school's side: a named
	 * captain plays in it; without one, the organiser runs it without playing. Players are optional.
	 */
	@Transactional
	public CompetitionResponse addTeam(UUID id, UUID teamId, String name, UUID captainId, Collection<UUID> playerIds, UUID me) {
		var competition = organised(id, me);
		var existing = entries.inCompetition(id);
		var names = directory.findAll(existing.stream().map(Entry::getTeamId).toList());
		requireNotDrawn(id, "The fixtures are drawn. Add teams before drawing them, or start a new league.");
		var now = clock.instant();
		if (teamId != null) {
			var team = directory.find(teamId).orElseThrow(() -> BusinessException.notFound("That team doesn’t exist any more."));
			if (names.containsKey(teamId) || names.values().stream().anyMatch(t -> t.name().equalsIgnoreCase(team.name()))) {
				throw BusinessException.conflict("%s is already in this league.".formatted(team.name()));
			}
			var entry = new Entry(id, teamId, !team.captainId().equals(me), now);
			if (entry.isInvited()) {
				entries.save(entry);
				events.publishEvent(new CompetitionEvents.TeamInvited(info(competition), team.name(), team.captainId(), me));
			}
			else {
				enter(entry, team, now);
			}
			return views.of(competition, me);
		}
		var trimmed = name == null ? "" : name.strip();
		if (names.values().stream().anyMatch(t -> t.name().equalsIgnoreCase(trimmed))) {
			throw BusinessException.conflict("%s is already in this league.".formatted(trimmed));
		}
		var captain = captainId == null ? me : captainId;
		if (captainId != null) {
			if (users.find(captainId).isEmpty()) {
				throw BusinessException.invalid("Pick a captain from your players.");
			}
			if (entries.isPlaying(id, captainId)) {
				throw BusinessException.conflict("%s is already in another team in this league.".formatted(firstName(captainId)));
			}
		}
		var tint = Competition.TEAM_TINTS.get(existing.size() % Competition.TEAM_TINTS.size());
		var wanted = playerIds == null ? List.<UUID>of() : playerIds.stream().distinct().filter(p -> !entries.isPlaying(id, p)).toList();
		var team = directory.create(trimmed, captain, captainId != null, tint, wanted);
		enter(new Entry(id, team.id(), false, now), team, now);
		return views.of(competition, me);
	}

	/** competitions.answerEntry: the invited team's captain says yes (the team is in) or no (it's dropped). */
	@Transactional
	public CompetitionResponse answerEntry(UUID id, UUID teamId, boolean accept, UUID me) {
		var competition = locked(id);
		var entry = entries.findById(new EntryId(id, teamId)).filter(Entry::isInvited)
			.orElseThrow(() -> BusinessException.notFound("That invitation has already been answered."));
		var team = directory.find(teamId).orElseThrow(() -> BusinessException.notFound("That team doesn’t exist any more."));
		if (!team.captainId().equals(me)) {
			throw BusinessException.conflict("Only %s can answer for %s.".formatted(firstName(team.captainId()), team.name()));
		}
		if (accept) {
			requireNotDrawn(id, "The fixtures are already drawn, so the league is full.");
			entry.accept(clock.instant());
			enter(entry, team, clock.instant());
		}
		else {
			entries.delete(entry);
		}
		events.publishEvent(new CompetitionEvents.EntryAnswered(info(competition), team.name(), competition.getOrganiserId(), me, accept));
		return views.of(competition, me);
	}

	/** competitions.removeTeam: organiser only, before the fixtures are drawn. The team itself stays. */
	@Transactional
	public CompetitionResponse removeTeam(UUID id, UUID teamId, UUID me) {
		var competition = organised(id, me);
		requireNotDrawn(id, "The fixtures are drawn, so teams can’t be dropped.");
		entries.delete(entry(id, teamId));
		return views.of(competition, me);
	}

	/** competitions.generateFixtures: organiser only. Everyone plays everyone once, a round a week, fixtures back to back on the day. */
	@Transactional
	public CompetitionResponse generateFixtures(UUID id, UUID me) {
		var competition = organised(id, me);
		requireNotDrawn(id, "The fixtures are already drawn.");
		var squads = entries.inCompetition(id);
		var cards = directory.findAll(squads.stream().map(Entry::getTeamId).toList());
		squads.stream().filter(Entry::isInvited).findFirst().ifPresent(waiting -> {
			throw BusinessException.conflict("%s hasn’t accepted yet. Wait for their captain, or drop them.".formatted(cards.get(waiting.getTeamId()).name()));
		});
		if (squads.size() < 3) {
			throw BusinessException.invalid("A league needs at least three teams.");
		}
		var byId = squads.stream().collect(Collectors.toMap(Entry::getTeamId, e -> e));
		var rounds = RoundRobin.rounds(squads.stream().map(Entry::getTeamId).toList());
		var firstDay = competition.getStartsAt().atZone(Market.get(Market.DEFAULT).zone());
		var specs = new ArrayList<Fixtures.FixtureSpec>();
		for (int round = 0; round < rounds.size(); round++) {
			var pairs = rounds.get(round);
			for (int i = 0; i < pairs.size(); i++) {
				var home = byId.get(pairs.get(i).home());
				var away = byId.get(pairs.get(i).away());
				var startsAt = firstDay.plusWeeks(round).plusMinutes((long) i * competition.getDurationMinutes()).toInstant();
				specs.add(new Fixtures.FixtureSpec(id, round + 1, home.getTeamId(), away.getTeamId(),
						"%s vs %s".formatted(cards.get(home.getTeamId()).name(), cards.get(away.getTeamId()).name()),
						competition.getSport(), competition.getFormat(), startsAt, competition.getDurationMinutes(), competition.getVenueKind(),
						competition.getVenueId(), competition.getVenueName(), competition.getVenueArea(), competition.getMapUrl(),
						competition.getOrganiserId(),
						squadOf(home, away)));
			}
		}
		fixtures.create(specs);
		competition.start();
		var players = squads.stream().flatMap(e -> e.playerIds().stream()).distinct().filter(p -> !p.equals(me)).toList();
		events.publishEvent(new CompetitionEvents.FixturesDrawn(info(competition), me, players, rounds.size(), competition.getStartsAt()));
		return views.of(competition, me);
	}

	/** competitions.addPlayers: captain or organiser. They join the team too, and upcoming fixtures pick them up. */
	@Transactional
	public CompetitionResponse addPlayers(UUID id, UUID teamId, Collection<UUID> userIds, UUID me) {
		var competition = locked(id);
		var entry = runBy(competition, teamId, me);
		var real = users.findAll(userIds).keySet();
		var adding = userIds.stream().distinct().filter(real::contains).filter(p -> !entries.isPlaying(id, p)).toList();
		if (adding.isEmpty()) {
			throw BusinessException.conflict("Those players are already in a team in this league.");
		}
		var now = clock.instant();
		adding.forEach(p -> entry.add(p, now));
		saveSquad(entry);
		directory.addMembers(teamId, adding, me, false);
		syncFixtures(competition, entry);
		var others = adding.stream().filter(p -> !p.equals(me)).toList();
		if (!others.isEmpty()) {
			events.publishEvent(new CompetitionEvents.AddedToSquad(info(competition), teamName(teamId), me, others));
		}
		return views.of(competition, me);
	}

	/** competitions.removePlayer: captain or organiser. Out of this league's squad; they stay in the team. */
	@Transactional
	public CompetitionResponse removePlayer(UUID id, UUID teamId, UUID userId, UUID me) {
		var competition = locked(id);
		var entry = runBy(competition, teamId, me);
		entry.remove(userId, captainOf(teamId));
		entries.saveAndFlush(entry);
		syncFixtures(competition, entry);
		return views.of(competition, me);
	}

	/** competitions.requestJoin: ask a team's captain for a place (in the team, and so its squad here). */
	@Transactional
	public CompetitionResponse requestJoin(UUID id, UUID teamId, UUID me) {
		var competition = locked(id);
		entry(id, teamId);
		if (entries.isPlaying(id, me)) {
			throw BusinessException.conflict("You’re already playing in this league.");
		}
		directory.requestJoin(teamId, me, false);
		events.publishEvent(new CompetitionEvents.SquadRequested(info(competition), teamName(teamId), captainOf(teamId), me));
		return views.of(competition, me);
	}

	/** competitions.answerRequest: the team's captain says yes or no. Yes puts them in the squad. */
	@Transactional
	public CompetitionResponse answerRequest(UUID id, UUID requestId, boolean accept, UUID me) {
		var competition = locked(id);
		var teamIds = entries.inCompetition(id).stream().map(Entry::getTeamId).toList();
		var request = directory.pendingRequests(teamIds).stream().filter(r -> r.id().equals(requestId)).findFirst()
			.orElseThrow(() -> BusinessException.notFound("That request has already been answered."));
		if (accept && entries.isPlaying(id, request.userId())) {
			throw BusinessException.conflict("They’ve joined another team in the meantime.");
		}
		directory.answerRequest(requestId, accept, me, false);
		if (accept) {
			var entry = entry(id, request.teamId());
			entry.add(request.userId(), clock.instant());
			saveSquad(entry);
			syncFixtures(competition, entry);
		}
		events.publishEvent(new CompetitionEvents.SquadAnswered(info(competition), teamName(request.teamId()), me, request.userId(), accept));
		return views.of(competition, me);
	}

	/** competitions.joinWithToken: from the team link a captain shared, into the team and its squad here. */
	@Transactional
	public CompetitionResponse joinWithToken(UUID id, String token, UUID me) {
		var competition = locked(id);
		var team = directory.byToken(token).filter(t -> entries.findById(new EntryId(id, t.id())).filter(e -> !e.isInvited()).isPresent())
			.orElseThrow(() -> BusinessException.notFound("That squad link doesn’t work any more. Ask the captain for a new one."));
		var entry = entry(id, team.id());
		if (entry.has(me)) {
			throw BusinessException.conflict("You’re already in %s.".formatted(team.name()));
		}
		if (entries.isPlaying(id, me)) {
			throw BusinessException.conflict("You’re already playing for another team in this league.");
		}
		entry.add(me, clock.instant());
		saveSquad(entry);
		directory.addMembers(team.id(), List.of(me), me, false);
		syncFixtures(competition, entry);
		events.publishEvent(new CompetitionEvents.JoinedByLink(info(competition), team.name(), team.captainId(), me, entry.playerIds().size()));
		return views.of(competition, me);
	}

	/**
	 * Someone joined a team (by its link, a request, or the captain adding them): they go into its
	 * squad in each league still on where they aren't playing for another team.
	 */
	@EventListener
	void on(TeamEvents.MemberJoined e) {
		for (var entry : entries.ofTeam(e.teamId())) {
			var competition = locked(entry.getCompetitionId());
			if (Competition.FINISHED.equals(competition.getStatus()) || entry.isInvited() || entry.has(e.userId())
					|| entries.isPlaying(competition.getId(), e.userId())) {
				continue;
			}
			entry.add(e.userId(), clock.instant());
			saveSquad(entry);
			syncFixtures(competition, entry);
		}
	}

	/** A team's first squad here: its members who aren't playing for another team in the league. */
	private void enter(Entry entry, TeamCard team, Instant now) {
		team.memberIds().stream().filter(p -> !entries.isPlaying(entry.getCompetitionId(), p)).forEach(p -> entry.add(p, now));
		saveSquad(entry);
	}

	/** Upcoming fixtures involving the team get its new squad (with their opponents'). */
	private void syncFixtures(Competition competition, Entry entry) {
		var squads = entries.inCompetition(competition.getId()).stream().collect(Collectors.toMap(Entry::getTeamId, e -> e));
		for (var f : fixtures.of(competition.getId())) {
			if (!f.homeTeamId().equals(entry.getTeamId()) && !f.awayTeamId().equals(entry.getTeamId())) {
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
	private void saveSquad(Entry entry) {
		try {
			entries.saveAndFlush(entry);
		}
		catch (DataIntegrityViolationException e) {
			throw BusinessException.conflict("Someone in that squad is already in another team in this league.");
		}
	}

	private static List<UUID> squadOf(Entry home, Entry away) {
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

	/** A team's squad here, run by the team's captain; the organiser can step in on any of them. */
	private Entry runBy(Competition competition, UUID teamId, UUID me) {
		var entry = entry(competition.getId(), teamId);
		var captain = captainOf(teamId);
		if (!captain.equals(me) && !competition.isOrganisedBy(me)) {
			throw BusinessException.conflict("Only %s or the organiser can change this squad.".formatted(firstName(captain)));
		}
		return entry;
	}

	private Entry entry(UUID competitionId, UUID teamId) {
		return entries.findById(new EntryId(competitionId, teamId))
			.orElseThrow(() -> BusinessException.notFound("That team isn’t in this league."));
	}

	private UUID captainOf(UUID teamId) {
		return directory.find(teamId).map(TeamCard::captainId).orElseThrow(() -> BusinessException.notFound("That team doesn’t exist any more."));
	}

	private String teamName(UUID teamId) {
		return directory.find(teamId).map(TeamCard::name).orElse("the team");
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
