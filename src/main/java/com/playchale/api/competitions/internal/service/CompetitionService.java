package com.playchale.api.competitions.internal.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.playchale.api.competitions.api.CompetitionEvents;
import com.playchale.api.competitions.api.CompetitionEvents.CompetitionInfo;
import com.playchale.api.competitions.internal.domain.Competition;
import com.playchale.api.competitions.internal.domain.CompetitionDetails;
import com.playchale.api.competitions.internal.domain.Entry;
import com.playchale.api.competitions.internal.domain.EntryId;
import com.playchale.api.competitions.internal.domain.Knockout;
import com.playchale.api.competitions.internal.domain.RoundRobin;
import com.playchale.api.competitions.internal.repository.CompetitionRepository;
import com.playchale.api.competitions.internal.repository.EntryRepository;
import com.playchale.api.games.api.Fixtures;
import com.playchale.api.market.Market;
import com.playchale.api.organisations.api.OrganisationAccess;
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

	private static final String NOT_FOUND = "That competition doesn’t exist any more.";

	/** A fixture nobody played: it counts as done when deciding whether a league is over. */
	private static final String CANCELLED = "cancelled";

	private final CompetitionRepository competitions;

	private final EntryRepository entries;

	private final TeamDirectory directory;

	private final CompetitionViews views;

	private final Fixtures fixtures;

	private final UserDirectory users;

	private final PitchBookings venues;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	private final OrganisationAccess organisations;

	private final CorporateOperationsService corporateOperations;

	CompetitionService(CompetitionRepository competitions, EntryRepository entries, TeamDirectory directory, CompetitionViews views,
			Fixtures fixtures, UserDirectory users, PitchBookings venues, ApplicationEventPublisher events, Clock clock, OrganisationAccess organisations, CorporateOperationsService corporateOperations) {
		this.competitions = competitions;
		this.entries = entries;
		this.directory = directory;
		this.views = views;
		this.fixtures = fixtures;
		this.users = users;
		this.venues = venues;
		this.events = events;
		this.clock = clock;
		this.organisations = organisations;
		this.corporateOperations = corporateOperations;
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
		return create(details, null, me);
	}

	@Transactional
	public CompetitionResponse create(CompetitionDetails details, UUID organisationId, UUID me) {
		if (organisationId != null) {
			organisations.requireAdmin(organisationId, me);
			if (!organisations.corporateEnabled(organisationId)) {
				throw BusinessException.conflict("Corporate operations are not enabled for this organisation yet.");
			}
		}
		var competition = new Competition(details, me, clock.instant());
		if (organisationId != null) {
			competition.runFor(organisationId);
		}
		if ("listed".equals(details.venueKind())) {
			var venue = venues.findVenue(details.venueId() == null ? new UUID(0, 0) : details.venueId())
				.orElseThrow(() -> BusinessException.invalid("That venue could not be found."));
			competition.playAt(venue.id(), venue.name(), venue.area());
			competition.placeIn(Market.get(venue.country()), venue.timezone());
		}
		else {
			competition.playAt(details.venueName(), details.venueArea(), details.venueMapUrl());
			competition.placeIn(Market.get(users.find(me).map(u -> u.country()).orElse(Market.DEFAULT)), details.timezone());
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
		undraw(competition, "A fixture has been played, so no more teams can come in. Start a new %s for them."
			.formatted(competition.noun()));
		var now = clock.instant();
		if (teamId != null) {
			var team = directory.find(teamId).orElseThrow(() -> BusinessException.notFound("That team doesn’t exist any more."));
			if (names.containsKey(teamId) || names.values().stream().anyMatch(t -> t.name().equalsIgnoreCase(team.name()))) {
				throw BusinessException.conflict("%s is already in this %s.".formatted(team.name(), competition.noun()));
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
			throw BusinessException.conflict("%s is already in this %s.".formatted(trimmed, competition.noun()));
		}
		var captain = captainId == null ? me : captainId;
		if (captainId != null) {
			if (users.find(captainId).isEmpty()) {
				throw BusinessException.invalid("Pick a captain from your players.");
			}
			if (entries.isPlaying(id, captainId)) {
				throw BusinessException.conflict("%s is already in another team in this %s.".formatted(firstName(captainId), competition.noun()));
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
			undraw(competition, "A fixture has been played, so the %s is full.".formatted(competition.noun()));
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
		undraw(competition, "A fixture has been played, so teams can’t be dropped now.");
		entries.delete(entry(id, teamId));
		return views.of(competition, me);
	}

	/**
	 * competitions.generateFixtures: organiser only. A league draws every round at once, everyone
	 * playing everyone, a round a week. A knockout draws its first round only — the rest follow as
	 * results come in, because who's in them depends on who wins.
	 */
	@Transactional
	public CompetitionResponse generateFixtures(UUID id, UUID me) {
		var competition = organised(id, me);
		// Drawing again is how a late entry is taken in: the old fixtures go, nobody having played them.
		undraw(competition, "A fixture has been played, so the draw stands.");
		var squads = entries.inCompetition(id);
		var cards = directory.findAll(squads.stream().map(Entry::getTeamId).toList());
		squads.stream().filter(Entry::isInvited).findFirst().ifPresent(waiting -> {
			throw BusinessException.conflict("%s hasn’t accepted yet. Wait for their captain, or drop them.".formatted(cards.get(waiting.getTeamId()).name()));
		});
		if (squads.size() < competition.fewestTeams()) {
			throw BusinessException.invalid(competition.isKnockout() ? "A tournament needs at least two teams."
					: "A league needs at least three teams.");
		}
		var rounds = competition.isKnockout() ? drawKnockout(competition, squads) : drawLeague(competition, squads, cards);
		competition.start();
		var players = squads.stream().flatMap(e -> e.playerIds().stream()).distinct().filter(p -> !p.equals(me)).toList();
		events.publishEvent(new CompetitionEvents.FixturesDrawn(info(competition), me, players, rounds, competition.getStartsAt()));
		return views.of(competition, me);
	}

	/** Every team plays every other once, a round a week, the day's fixtures back to back. */
	private int drawLeague(Competition competition, List<Entry> squads, Map<UUID, TeamCard> cards) {
		var byId = squads.stream().collect(Collectors.toMap(Entry::getTeamId, e -> e));
		var rounds = RoundRobin.rounds(squads.stream().map(Entry::getTeamId).toList());
		// Matchdays a week apart in local time, so a clock change doesn't move kick-off.
		var firstDay = competition.getStartsAt().atZone(competition.zone());
		var specs = new ArrayList<Fixtures.FixtureSpec>();
		for (int round = 0; round < rounds.size(); round++) {
			var pairs = rounds.get(round);
			for (int i = 0; i < pairs.size(); i++) {
				var home = byId.get(pairs.get(i).home());
				var away = byId.get(pairs.get(i).away());
				var startsAt = firstDay.plusWeeks(round).plusMinutes((long) i * competition.getDurationMinutes()).toInstant();
				specs.add(spec(competition, cards, round + 1, home, away, startsAt, null, false));
			}
		}
		fixtures.create(specs);
		return rounds.size();
	}

	/**
	 * The first round of the bracket. Teams that drew a bye have nothing to play, so they wait for
	 * the round after, which is drawn once this one is done.
	 */
	private int drawKnockout(Competition competition, List<Entry> squads) {
		var draw = Knockout.firstRound(squads.stream().map(Entry::getTeamId).toList());
		playRound(competition, draw.ties(), 1);
		return Knockout.rounds(squads.size());
	}

	/**
	 * Creates the fixtures for one round of a bracket, back to back from the round's kick-off: a
	 * round a week, as a league's matchdays are.
	 */
	private void playRound(Competition competition, List<Knockout.Tie> ties, int round) {
		if (ties.isEmpty()) {
			return;
		}
		var cards = directory.findAll(ties.stream().flatMap(t -> Stream.of(t.home(), t.away())).toList());
		var squads = entries.inCompetition(competition.getId()).stream().collect(Collectors.toMap(Entry::getTeamId, e -> e));
		var day = competition.getStartsAt().atZone(competition.zone()).plusWeeks(round - 1L);
		var specs = new ArrayList<Fixtures.FixtureSpec>();
		for (int i = 0; i < ties.size(); i++) {
			var tie = ties.get(i);
			var startsAt = day.plusMinutes((long) i * competition.getDurationMinutes()).toInstant();
			specs.add(spec(competition, cards, round, squads.get(tie.home()), squads.get(tie.away()), startsAt, tie.slot(), true));
		}
		fixtures.create(specs);
	}

	/** One fixture to create, with the two squads in it. */
	private Fixtures.FixtureSpec spec(Competition competition, Map<UUID, TeamCard> cards, int round, Entry home, Entry away,
			Instant startsAt, Integer slot, boolean decider) {
		return new Fixtures.FixtureSpec(competition.getId(), round, home.getTeamId(), away.getTeamId(),
				"%s vs %s".formatted(cards.get(home.getTeamId()).name(), cards.get(away.getTeamId()).name()),
				competition.getSport(), competition.getFormat(), startsAt, competition.getDurationMinutes(), competition.getVenueKind(),
				competition.getVenueId(), competition.getVenueName(), competition.getVenueArea(), competition.getMapUrl(),
				competition.getOrganiserId(), squadOf(home, away), competition.getCountry(), competition.getTimezone(), slot, decider);
	}

	/**
	 * A knockout moves on: once every tie in the latest round has a winner, the next round is drawn
	 * from those winners and anyone whose bye left them waiting. When the final is won, it's over.
	 * Called after a result goes in, so the bracket fills itself without the organiser doing anything.
	 */
	@Transactional
	public void advance(UUID competitionId) {
		var competition = competitions.findById(competitionId).orElse(null);
		if (competition == null || !Competition.RUNNING.equals(competition.getStatus())) {
			return;
		}
		var played = fixtures.of(competitionId);
		if (played.isEmpty()) {
			return;
		}
		// A league has no final to win: it's over once every fixture has been played or called off.
		if (!competition.isKnockout()) {
			if (played.stream().allMatch(f -> f.played() || CANCELLED.equals(f.status()))) {
				competition.finish();
			}
			return;
		}
		var round = played.stream().mapToInt(Fixtures.FixtureSummary::round).max().orElse(0);
		var latest = played.stream().filter(f -> f.round() == round).toList();
		if (latest.stream().anyMatch(f -> f.winner() == null)) {
			return;
		}
		// Whoever came through this round, at the slot they came from, plus byes still waiting.
		var through = new TreeMap<Integer, UUID>();
		latest.forEach(f -> through.put(f.slot() == null ? 0 : f.slot(), f.winner()));
		waiting(competition, played, round).forEach(through::putIfAbsent);
		if (through.size() < 2) {
			competition.finish();
			return;
		}
		playRound(competition, Knockout.nextRound(round + 1, through), round + 1);
	}

	/**
	 * competitions.finish: an organiser calls time. Useful when the last round was never played, or a
	 * knockout was abandoned — a league that plays every fixture finishes on its own.
	 */
	@Transactional
	public CompetitionResponse finish(UUID id, UUID me) {
		var competition = organised(id, me);
		if (Competition.DRAFT.equals(competition.getStatus())) {
			throw BusinessException.conflict("The draw hasn’t been made yet, so there’s nothing to finish.");
		}
		competition.finish();
		return views.of(competition, me);
	}

	/** competitions.reopen: called time too early. Puts it back to running so results can go in. */
	@Transactional
	public CompetitionResponse reopen(UUID id, UUID me) {
		var competition = organised(id, me);
		if (!Competition.FINISHED.equals(competition.getStatus())) {
			throw BusinessException.conflict("That’s still going.");
		}
		competition.start();
		return views.of(competition, me);
	}

	/**
	 * Teams whose bye means they haven't played yet: everyone entered, less everyone who has been in
	 * a tie so far. Each waits at the slot their bye gave them in the first round.
	 */
	private SortedMap<Integer, UUID> waiting(Competition competition, List<Fixtures.FixtureSummary> played, int round) {
		if (round > 1) {
			return new TreeMap<>();
		}
		var entered = entries.inCompetition(competition.getId()).stream().map(Entry::getTeamId).toList();
		var draw = Knockout.firstRound(entered);
		return new TreeMap<>(draw.byes());
	}

	/** competitions.addPlayers: captain or organiser. They join the team too, and upcoming fixtures pick them up. */
	@Transactional
	public CompetitionResponse addPlayers(UUID id, UUID teamId, Collection<UUID> userIds, UUID me) {
		var competition = locked(id);
		var entry = runBy(competition, teamId, me);
		var real = users.findAll(userIds).keySet();
		var adding = userIds.stream().distinct().filter(real::contains).filter(p -> !entries.isPlaying(id, p)).toList();
		if (adding.isEmpty()) {
			throw BusinessException.conflict("Those players are already in a team in this %s.".formatted(competition.noun()));
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
			throw BusinessException.conflict("You’re already playing in this %s.".formatted(competition.noun()));
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
			throw BusinessException.conflict("You’re already playing for another team in this %s.".formatted(competition.noun()));
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
			throw BusinessException.conflict("Someone in that squad is already in another team here.");
		}
	}

	private static List<UUID> squadOf(Entry home, Entry away) {
		var squad = new LinkedHashSet<UUID>(home.playerIds());
		squad.addAll(away.playerIds());
		return List.copyOf(squad);
	}

	/**
	 * Makes room to change who's taking part. Before the draw there's nothing to do. After it, the
	 * fixtures are thrown away and the competition goes back to being set up, so the organiser draws
	 * again with everyone in — a late entry shouldn't mean starting the whole thing over. Once a
	 * fixture has been played the draw stands, and this refuses.
	 */
	private void undraw(Competition competition, String message) {
		var drawn = fixtures.of(competition.getId());
		if (drawn.isEmpty()) {
			return;
		}
		if (drawn.stream().anyMatch(Fixtures.FixtureSummary::played)) {
			throw BusinessException.conflict(message);
		}
		fixtures.discard(competition.getId());
		competition.backToDraft();
	}

	/**
	 * competitions.addOrganiser: hands someone else the league's controls, so a sports committee can
	 * run it together. Only the organiser who set it up can, and only to someone already on PlayChale.
	 */
	@Transactional
	public CompetitionResponse addOrganiser(UUID id, UUID userId, UUID me) {
		var competition = owned(id, me);
		if (users.find(userId).isEmpty()) {
			throw BusinessException.notFound("That player could not be found.");
		}
		competition.addOrganiser(userId);
		return views.of(competition, me);
	}

	/** competitions.removeOrganiser: takes the controls back. */
	@Transactional
	public CompetitionResponse removeOrganiser(UUID id, UUID userId, UUID me) {
		var competition = owned(id, me);
		competition.removeOrganiser(userId);
		return views.of(competition, me);
	}

	private Competition owned(UUID id, UUID me) {
		var competition = locked(id);
		if (!competition.isOwnedBy(me)) {
			throw BusinessException.conflict("Only the organiser who set this %s up can change who runs it.".formatted(competition.noun()));
		}
		return competition;
	}

	private Competition organised(UUID id, UUID me) {
		var competition = locked(id);
		if (!competition.isOrganisedBy(me) && !(competition.isCorporate() && (organisations.isAdmin(competition.getOrganisationId(), me) || corporateOperations.canManage(competition.getId(), me)))) {
			throw BusinessException.conflict("Only the organiser can change this %s.".formatted(competition.noun()));
		}
		return competition;
	}

	/** A team's squad here, run by the team's captain; the organiser can step in on any of them. */
	private Entry runBy(Competition competition, UUID teamId, UUID me) {
		var entry = entry(competition.getId(), teamId);
		var captain = captainOf(teamId);
		if (!captain.equals(me) && !competition.isOrganisedBy(me) && !(competition.isCorporate() && (organisations.isAdmin(competition.getOrganisationId(), me) || corporateOperations.canManage(competition.getId(), me)))) {
			throw BusinessException.conflict("Only %s or the organiser can change this squad.".formatted(firstName(captain)));
		}
		return entry;
	}

	private Entry entry(UUID competitionId, UUID teamId) {
		return entries.findById(new EntryId(competitionId, teamId))
			.orElseThrow(() -> BusinessException.notFound("That team isn’t taking part."));
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

	private static CompetitionInfo info(Competition c) {
		return new CompetitionInfo(c.getId(), c.getName(), c.noun(), c.getCountry(), c.getTimezone());
	}

}
