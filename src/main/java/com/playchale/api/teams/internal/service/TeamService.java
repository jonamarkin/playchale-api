package com.playchale.api.teams.internal.service;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.teams.api.JoinRequestCard;
import com.playchale.api.teams.api.TeamCard;
import com.playchale.api.teams.api.TeamDirectory;
import com.playchale.api.teams.api.TeamEvents;
import com.playchale.api.teams.api.TeamGames;
import com.playchale.api.teams.api.TeamLeagues;
import com.playchale.api.teams.api.TeamMemberships;
import com.playchale.api.teams.internal.domain.JoinRequest;
import com.playchale.api.teams.internal.domain.Team;
import com.playchale.api.teams.internal.repository.JoinRequestRepository;
import com.playchale.api.teams.internal.repository.TeamRepository;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.users.api.UserSummary;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Teams: anyone can set one up and captain it; people join by its link, by asking, or by the captain
 * adding them. Every change to a team locks it, so two at once can't trip over each other.
 */
@Service
public class TeamService implements TeamDirectory, TeamMemberships {

	private final TeamRepository teams;

	private final JoinRequestRepository requests;

	private final UserDirectory users;

	private final ObjectProvider<TeamLeagues> leagues;

	private final ObjectProvider<TeamGames> games;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	TeamService(TeamRepository teams, JoinRequestRepository requests, UserDirectory users, ObjectProvider<TeamLeagues> leagues,
			ObjectProvider<TeamGames> games, ApplicationEventPublisher events, Clock clock) {
		this.teams = teams;
		this.requests = requests;
		this.users = users;
		this.leagues = leagues;
		this.games = games;
		this.events = events;
		this.clock = clock;
	}

	/* ------------------------------------------------------------------ for the web app */

	/** teams.create: the creator captains it and plays for it. */
	@Transactional
	public TeamResponse create(String name, String tint, Collection<UUID> memberIds, UUID me) {
		var team = teams.save(new Team(name, me, true, tint, clock.instant()));
		add(team, memberIds == null ? List.of() : memberIds, me, true);
		return view(team, me);
	}

	/** teams.get */
	@Transactional(readOnly = true)
	public Optional<TeamResponse> get(UUID id, UUID viewer) {
		return teams.findById(id).map(t -> view(t, viewer));
	}

	/** teams.mine: teams the player is in or captains. */
	@Transactional(readOnly = true)
	public List<TeamResponse> mine(UUID me) {
		return teams.involving(me).stream().map(t -> view(t, me)).toList();
	}

	/** teams.search: teams whose name has the query in it (at least two letters), to challenge or look up. */
	@Transactional(readOnly = true)
	public List<TeamResponse.Found> search(String query, UUID viewer) {
		var q = query == null ? "" : query.strip();
		if (q.length() < 2) {
			return List.of();
		}
		var found = teams.findTop20ByNameContainingIgnoreCaseOrderByName(q);
		var captains = users.findAll(found.stream().map(Team::getCaptainId).distinct().toList());
		return found.stream().map(t -> new TeamResponse.Found(t.getId(), t.getName(), t.getTint(), t.getCaptainId(),
				shown(captains.get(t.getCaptainId()), viewer), t.memberIds().size())).toList();
	}

	/** teams.update: captain only. Name, colours, or handing the armband over. */
	@Transactional
	public TeamResponse update(UUID id, String name, String tint, UUID captainId, UUID me) {
		var team = captained(id, me);
		if (name != null) {
			team.rename(name);
		}
		if (tint != null) {
			team.recolour(tint);
		}
		if (captainId != null) {
			team.handOver(captainId);
		}
		return view(team, me);
	}

	/** teams.addMembers: captain only. They're told they're in. */
	@Transactional
	public TeamResponse addMembers(UUID id, Collection<UUID> userIds, UUID me) {
		var team = captained(id, me);
		if (add(team, userIds, me, true).isEmpty()) {
			throw BusinessException.conflict("They’re already in %s.".formatted(team.getName()));
		}
		return view(team, me);
	}

	/** teams.removeMember: the captain takes someone out, or someone leaves. */
	@Transactional
	public TeamResponse removeMember(UUID id, UUID userId, UUID me) {
		var team = locked(id);
		if (!userId.equals(me) && !team.isCaptain(me)) {
			throw BusinessException.conflict("Only %s can change the team.".formatted(firstName(team.getCaptainId())));
		}
		team.remove(userId);
		return view(team, me);
	}

	/** teams.requestJoin: ask the captain for a place. */
	@Transactional
	public TeamResponse requestJoin(UUID id, UUID me) {
		requestJoin(id, me, true);
		return view(locked(id), me);
	}

	/** teams.answerRequest: the captain says yes or no. */
	@Transactional
	public TeamResponse answerRequest(UUID id, UUID requestId, boolean accept, UUID me) {
		var request = requests.findById(requestId).filter(r -> r.getTeamId().equals(id)).map(this::card)
			.orElseThrow(() -> BusinessException.notFound("That request has already been answered."));
		answerRequest(request.id(), accept, me, true);
		return view(locked(id), me);
	}

	/** teams.joinWithToken: from the link a captain shared. */
	@Transactional
	public TeamResponse joinWithToken(UUID id, String token, UUID me) {
		var team = locked(id);
		if (!team.getJoinToken().equals(token == null ? "" : token)) {
			throw BusinessException.notFound("That team link doesn’t work any more. Ask the captain for a new one.");
		}
		if (team.has(me)) {
			throw BusinessException.conflict("You’re already in %s.".formatted(team.getName()));
		}
		add(team, List.of(me), me, true);
		return view(team, me);
	}

	/** teams.remove: captain only, and not once it's in a league that has started. */
	@Transactional
	public void delete(UUID id, UUID me) {
		var team = captained(id, me);
		leaguesOf(id).stream().filter(TeamLeagues.TeamLeague::started).findFirst().ifPresent(league -> {
			throw BusinessException.conflict("%s is in %s. A team can’t be deleted once its league has started.".formatted(team.getName(), league.name()));
		});
		if (!gamesOf(id).upcoming().isEmpty()) {
			throw BusinessException.conflict("%s has a game coming up. Call it off, or turn the challenge down, first.".formatted(team.getName()));
		}
		teams.delete(team);
	}

	/* ------------------------------------------------------------------ for other modules */

	@Override
	@Transactional(readOnly = true)
	public Optional<TeamCard> find(UUID teamId) {
		return teams.findById(teamId).map(TeamService::card);
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, TeamCard> findAll(Collection<UUID> teamIds) {
		return teamIds.isEmpty() ? Map.of() : teams.findAllById(teamIds).stream().collect(Collectors.toMap(Team::getId, TeamService::card));
	}

	@Override
	@Transactional(readOnly = true)
	public List<TeamCard> teamsOf(UUID userId) {
		return teams.involving(userId).stream().map(TeamService::card).toList();
	}

	@Override
	@Transactional
	public TeamCard create(String name, UUID captainId, boolean captainPlays, String tint, Collection<UUID> memberIds) {
		var team = teams.save(new Team(name, captainId, captainPlays, tint, clock.instant()));
		add(team, memberIds, captainId, false);
		return card(team);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<TeamCard> byToken(String token) {
		return token == null || token.isBlank() ? Optional.empty() : teams.findByJoinToken(token).map(TeamService::card);
	}

	@Override
	@Transactional(readOnly = true)
	public String joinToken(UUID teamId) {
		return teams.findById(teamId).map(Team::getJoinToken).orElse("");
	}

	@Override
	@Transactional
	public List<UUID> addMembers(UUID teamId, Collection<UUID> userIds, UUID addedBy, boolean announce) {
		return add(locked(teamId), userIds, addedBy, announce);
	}

	@Override
	@Transactional
	public void requestJoin(UUID teamId, UUID userId, boolean announce) {
		var team = locked(teamId);
		if (team.has(userId)) {
			throw BusinessException.conflict("You’re already in %s.".formatted(team.getName()));
		}
		if (requests.existsByTeamIdAndUserIdAndStatus(teamId, userId, JoinRequest.PENDING)) {
			throw BusinessException.conflict("%s already has your request.".formatted(team.getName()));
		}
		requests.save(new JoinRequest(teamId, userId, clock.instant()));
		events.publishEvent(new TeamEvents.JoinRequested(teamId, team.getName(), team.getCaptainId(), userId, announce));
	}

	@Override
	@Transactional(readOnly = true)
	public List<JoinRequestCard> pendingRequests(Collection<UUID> teamIds) {
		return teamIds.isEmpty() ? List.of()
				: requests.findByTeamIdInAndStatusOrderByCreatedAt(teamIds, JoinRequest.PENDING).stream().map(this::card).toList();
	}

	@Override
	@Transactional
	public JoinRequestCard answerRequest(UUID requestId, boolean accept, UUID captain, boolean announce) {
		var request = requests.findById(requestId).filter(JoinRequest::isPending)
			.orElseThrow(() -> BusinessException.notFound("That request has already been answered."));
		var team = locked(request.getTeamId());
		if (!team.isCaptain(captain)) {
			throw BusinessException.conflict("Only %s can answer requests for %s.".formatted(firstName(team.getCaptainId()), team.getName()));
		}
		request.answer(accept, clock.instant());
		events.publishEvent(new TeamEvents.RequestAnswered(team.getId(), team.getName(), captain, request.getUserId(), accept, announce));
		if (accept) {
			add(team, List.of(request.getUserId()), captain, false);
		}
		return card(request);
	}

	@Override
	@Transactional(readOnly = true)
	public List<String> teamNames(UUID userId) {
		return teams.playedForBy(userId).stream().map(Team::getName).distinct().toList();
	}

	/* ------------------------------------------------------------------ */

	/** Adds real players who aren't in yet, and says so. Returns who was added. */
	private List<UUID> add(Team team, Collection<UUID> userIds, UUID addedBy, boolean announce) {
		var real = users.findAll(userIds).keySet();
		var now = clock.instant();
		var added = userIds.stream().distinct().filter(real::contains).filter(u -> team.add(u, now)).toList();
		teams.saveAndFlush(team);
		added.forEach(u -> events.publishEvent(new TeamEvents.MemberJoined(team.getId(), team.getName(), team.getCaptainId(), u, addedBy, announce)));
		return added;
	}

	TeamResponse view(Team team, UUID viewer) {
		var captain = team.isCaptain(viewer);
		var pending = captain ? requests.findByTeamIdInAndStatusOrderByCreatedAt(List.of(team.getId()), JoinRequest.PENDING) : List.<JoinRequest>of();
		var people = users.findAll(Stream.of(Stream.of(team.getCaptainId()), team.memberIds().stream(), pending.stream().map(JoinRequest::getUserId))
			.flatMap(s -> s).distinct().toList());
		var requested = viewer != null && !team.has(viewer) && requests.existsByTeamIdAndUserIdAndStatus(team.getId(), viewer, JoinRequest.PENDING);
		var played = gamesOf(team.getId());
		return new TeamResponse(team.getId(), team.getName(), team.getTint(), team.getCaptainId(), shown(people.get(team.getCaptainId()), viewer),
				team.memberIds(), team.memberIds().stream().map(people::get).filter(u -> u != null).map(u -> u.as(viewer)).toList(),
				captain ? team.getJoinToken() : "", team.getCreatedAt(), leaguesOf(team.getId()),
				captain ? pending.stream().map(r -> new TeamResponse.RequestView(r.getId(), r.getTeamId(), r.getUserId(), r.getStatus(),
						r.getCreatedAt(), shown(people.get(r.getUserId()), viewer))).toList() : null,
				requested, played.record(), played.upcoming(), played.recent());
	}

	private TeamGames.Summary gamesOf(UUID teamId) {
		return games.stream().findFirst().map(g -> g.of(teamId))
			.orElseGet(() -> new TeamGames.Summary(new TeamGames.Record(0, 0, 0, 0), List.of(), List.of()));
	}

	private List<TeamLeagues.TeamLeague> leaguesOf(UUID teamId) {
		return leagues.stream().findFirst().map(l -> l.leaguesOf(teamId)).orElseGet(List::of);
	}

	private Team captained(UUID id, UUID me) {
		var team = locked(id);
		if (!team.isCaptain(me)) {
			throw BusinessException.conflict("Only %s can change the team.".formatted(firstName(team.getCaptainId())));
		}
		return team;
	}

	private Team locked(UUID id) {
		return teams.lockById(id).orElseThrow(() -> BusinessException.notFound("That team doesn’t exist any more."));
	}

	private String firstName(UUID userId) {
		return users.find(userId).map(u -> u.name().split(" ")[0]).filter(n -> !n.isBlank()).orElse("the captain");
	}

	private static UserSummary shown(UserSummary user, UUID viewer) {
		return user == null ? null : user.as(viewer);
	}

	static TeamCard card(Team t) {
		return new TeamCard(t.getId(), t.getName(), t.getCaptainId(), t.memberIds(), t.getTint(), t.getCreatedAt());
	}

	private JoinRequestCard card(JoinRequest r) {
		return new JoinRequestCard(r.getId(), r.getTeamId(), r.getUserId(), r.getStatus(), r.getCreatedAt());
	}

}
