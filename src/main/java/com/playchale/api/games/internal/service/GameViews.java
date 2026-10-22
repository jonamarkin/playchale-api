package com.playchale.api.games.internal.service;

import com.playchale.api.games.api.FixtureOrganisers;
import com.playchale.api.games.api.FixtureTeams;
import com.playchale.api.games.api.GameResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.playchale.api.games.internal.domain.Game;
import com.playchale.api.games.internal.domain.GameInvite;
import com.playchale.api.games.internal.domain.GameResult;
import com.playchale.api.games.internal.domain.GameSeries;
import com.playchale.api.games.internal.domain.Participant;
import com.playchale.api.games.internal.domain.ResultLine;
import com.playchale.api.games.internal.domain.ResultVote;
import com.playchale.api.games.internal.domain.VoteTally;
import com.playchale.api.games.internal.repository.GameInviteRepository;
import com.playchale.api.games.internal.repository.GameResultRepository;
import com.playchale.api.games.internal.repository.GameSeriesRepository;
import com.playchale.api.games.internal.repository.ResultVotes;
import com.playchale.api.teams.api.TeamCard;
import com.playchale.api.teams.api.TeamDirectory;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.users.api.UserSummary;
import com.playchale.api.venues.api.PitchBookings;
import com.playchale.api.venues.api.VenueLocation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Builds what the web app shows for games, looking up everyone they mention in one query however
 * many games there are. Players see each other without phone numbers; the viewer sees their own.
 */
@Component
class GameViews {

	/** Behind a guest's initials: a neutral grey, since they have no profile colour yet. */
	private static final String GUEST_TINT = "#d7ded9";

	private final UserDirectory users;

	private final GameResultRepository results;

	private final ObjectProvider<FixtureTeams> fixtureTeams;

	private final ObjectProvider<FixtureOrganisers> fixtureOrganisers;

	private final PitchBookings venues;

	private final GameInviteRepository invites;

	private final TeamDirectory teams;

	private final GameSeriesRepository series;

	private final ResultVotes votes;

	private final Clock clock;

	GameViews(UserDirectory users, GameResultRepository results, ObjectProvider<FixtureTeams> fixtureTeams,
			ObjectProvider<FixtureOrganisers> fixtureOrganisers, PitchBookings venues, GameInviteRepository invites, TeamDirectory teams,
			GameSeriesRepository series, ResultVotes votes, Clock clock) {
		this.users = users;
		this.results = results;
		this.fixtureTeams = fixtureTeams;
		this.fixtureOrganisers = fixtureOrganisers;
		this.venues = venues;
		this.invites = invites;
		this.teams = teams;
		this.series = series;
		this.votes = votes;
		this.clock = clock;
	}

	GameResponse of(Game game, UUID viewer) {
		return of(List.of(game), viewer).getFirst();
	}

	List<GameResponse> of(Collection<Game> games, UUID viewer) {
		var allInvites = games.isEmpty() ? List.<GameInvite>of() : invites.ofGames(games.stream().map(Game::getId).toList());
		// How many are asked and haven't answered: anyone may see the number, so a game whose players
		// are still being asked (a weekly one, just opened) doesn't look empty.
		var asked = allInvites.stream().filter(GameInvite::isPending).collect(Collectors.groupingBy(GameInvite::getGameId, Collectors.counting()));
		// Invites the viewer may see: all of them in games they host, their own anywhere else.
		var invitesByGame = viewer == null ? Map.<UUID, List<GameInvite>>of()
				: allInvites.stream()
					.filter(i -> i.getUserId().equals(viewer) || games.stream().anyMatch(g -> g.getId().equals(i.getGameId()) && g.isHost(viewer)))
					.collect(Collectors.groupingBy(GameInvite::getGameId));
		var ids = Stream.concat(games.stream()
			.flatMap(g -> Stream.concat(Stream.of(g.getHostId()), g.getParticipants().stream().map(Participant::getUserId))),
				invitesByGame.values().stream().flatMap(List::stream).map(GameInvite::getUserId))
			.filter(Objects::nonNull)
			.distinct()
			.toList();
		var people = users.findAll(ids);
		// Teams named by invites and friendlies, in one lookup.
		var namedTeams = Stream.concat(invitesByGame.values().stream().flatMap(List::stream).map(GameInvite::getTeamId),
				games.stream().filter(Game::isFriendly).flatMap(g -> Stream.of(g.getHomeTeamId(), g.getAwayTeamId())))
			.filter(Objects::nonNull).distinct().toList();
		var standingTeams = namedTeams.isEmpty() ? Map.<UUID, TeamCard>of() : teams.findAll(namedTeams);
		var teamNames = standingTeams.values().stream().collect(Collectors.toMap(TeamCard::id, TeamCard::name));
		var played = games.stream().filter(g -> Game.COMPLETED.equals(g.getStatus())).map(Game::getId).toList();
		var resultsByGame = played.isEmpty() ? Map.<UUID, GameResult>of()
				: results.findAllById(played).stream().collect(Collectors.toMap(GameResult::getGameId, r -> r));
		var votesByGame = votes.ofGames(resultsByGame.keySet());
		var now = clock.instant();
		// Fixtures' teams, per league: a team can be in several, with a different squad in each.
		var teamIdsByLeague = games.stream().filter(g -> g.getCompetitionId() != null).collect(Collectors.groupingBy(Game::getCompetitionId,
				Collectors.flatMapping(g -> Stream.of(g.getHomeTeamId(), g.getAwayTeamId()), Collectors.toSet())));
		var teamsByLeague = new HashMap<UUID, Map<UUID, FixtureTeams.TeamCard>>();
		fixtureTeams.stream().findFirst().ifPresent(f -> teamIdsByLeague.forEach((league, teamIds) -> teamsByLeague.put(league, f.teams(league, teamIds))));
		// Competitions the viewer runs, asked once each however many of their fixtures are on the page.
		var organised = viewer == null ? Set.<UUID>of()
				: fixtureOrganisers.stream().findFirst()
					.map(o -> teamIdsByLeague.keySet().stream().filter(league -> o.organisedBy(league, viewer)).collect(Collectors.toSet()))
					.orElse(Set.of());
		// Partner venues' own links and pins, in one query: a pin the owner adds later reaches every game there.
		var places = venues.locations(games.stream().map(Game::getVenueId).filter(Objects::nonNull).distinct().toList());
		var seriesRefs = seriesRefs(games, viewer);
		return games.stream().map(g -> view(g, viewer, people,
				resultsByGame.containsKey(g.getId()) ? result(resultsByGame.get(g.getId()), votesByGame.getOrDefault(g.getId(), List.of()), viewer, now) : null,
				g.getCompetitionId() == null ? Map.of() : teamsByLeague.getOrDefault(g.getCompetitionId(), Map.of()), places,
				invites(invitesByGame.get(g.getId()), people, teamNames, viewer), friendly(g, standingTeams),
				g.getCompetitionId() != null && organised.contains(g.getCompetitionId()),
				g.getSeriesId() == null ? null : seriesRefs.get(g.getSeriesId()), asked.getOrDefault(g.getId(), 0L).intValue())).toList();
	}

	/** The repeating games these games belong to, and whether the viewer asked not to be invited to each, in two queries. */
	private Map<UUID, GameResponse.SeriesRef> seriesRefs(Collection<Game> games, UUID viewer) {
		var ids = games.stream().map(Game::getSeriesId).filter(Objects::nonNull).distinct().toList();
		if (ids.isEmpty()) {
			return Map.of();
		}
		var optedOut = viewer == null ? Set.<UUID>of() : Set.copyOf(series.optedOut(viewer, ids));
		return series.findAllById(ids).stream().collect(Collectors.toMap(GameSeries::getId, s -> seriesRef(s, viewer == null ? null : optedOut.contains(s.getId()))));
	}

	static GameResponse.SeriesRef seriesRef(GameSeries s, Boolean optedOut) {
		return new GameResponse.SeriesRef(s.getId(), s.getFrequency(), s.getWeekday(), s.getWeekOfMonth(), s.getKickOff().format(KICK_OFF),
				s.getStatus(), s.getNextStartsAt(), s.getOpensAt(), optedOut);
	}

	private static final DateTimeFormatter KICK_OFF = DateTimeFormatter.ofPattern("HH:mm");

	/** Invites as the viewer sees them, or null (left out) when there are none to show. */
	private static List<GameResponse.InviteResponse> invites(List<GameInvite> invites, Map<UUID, UserSummary> people,
			Map<UUID, String> teamNames, UUID viewer) {
		if (invites == null) {
			return null;
		}
		return invites.stream().filter(i -> people.containsKey(i.getUserId()))
			.map(i -> new GameResponse.InviteResponse(i.getUserId(), i.getTeamId(), i.getTeamId() == null ? null : teamNames.get(i.getTeamId()),
					i.getInvitedBy(), i.getStatus(), i.getInvitedAt(), i.getAnsweredAt(), people.get(i.getUserId()).as(viewer)))
			.toList();
	}

	/** A friendly's sides, or null for any other game (or once one of its teams is deleted). */
	private static GameResponse.FriendlyResponse friendly(Game g, Map<UUID, TeamCard> teams) {
		if (!g.isFriendly()) {
			return null;
		}
		var home = teams.get(g.getHomeTeamId());
		var away = teams.get(g.getAwayTeamId());
		if (home == null || away == null) {
			return null;
		}
		var side = (Function<TeamCard, GameResponse.TeamSide>) t -> new GameResponse.TeamSide(t.id(), t.name(), t.tint(),
				t.captainId(), g.getParticipants().stream().filter(p -> t.id().equals(p.getTeamId())).map(Participant::playerKey).toList());
		return new GameResponse.FriendlyResponse(side.apply(home), side.apply(away), g.getOpponentStatus());
	}

	private GameResponse view(Game g, UUID viewer, Map<UUID, UserSummary> people, GameResponse.ResultResponse result,
			Map<UUID, FixtureTeams.TeamCard> teams, Map<UUID, VenueLocation> places, List<GameResponse.InviteResponse> invites,
			GameResponse.FriendlyResponse friendly, boolean organiser, GameResponse.SeriesRef series, int asked) {
		var hostView = g.isHost(viewer);
		var place = g.getVenueId() != null ? places.get(g.getVenueId()) : new VenueLocation(g.getMapUrl(), g.getPin());
		var pin = place == null || place.pin() == null ? null : place.pin().view();
		var venue = new GameResponse.VenueRef(g.getVenueKind(), g.getVenueId(), g.getVenueName(), g.getVenueArea(), g.getPitchId(),
				g.getPitchName(), place == null ? null : place.mapUrl(), pin);
		var participants = g.getParticipants().stream()
			.map(p -> new GameResponse.ParticipantResponse(p.isGuest() ? "" : p.getUserId().toString(), p.getJoinedAt(), p.isPaid(),
					p.getPaymentId(), p.getPaidVia(), p.getRemindedAt(), guest(p, hostView), p.getAttended(), p.getAttendedAt()))
			.toList();
		var players = g.getParticipants().stream()
			.map(p -> p.isGuest() ? guestPlayer(p, hostView) : player(p, people.get(p.getUserId()), viewer))
			.filter(Objects::nonNull)
			.toList();
		var host = people.get(g.getHostId());
		GameResponse.FixtureRef fixture = null;
		GameResponse.FixtureTeamsResponse fixtureTeams = null;
		if (g.getCompetitionId() != null) {
			fixture = new GameResponse.FixtureRef(g.getCompetitionId(), g.getFixtureRound(), g.getHomeTeamId(), g.getAwayTeamId(),
					g.getFixtureSlot(), g.isDecider(), organiser);
			var home = teams.get(g.getHomeTeamId());
			var away = teams.get(g.getAwayTeamId());
			fixtureTeams = home == null || away == null ? null : new GameResponse.FixtureTeamsResponse(home, away);
		}
		return new GameResponse(g.getId(), g.getSport(), g.getFormat(), g.getTitle(), g.getStartsAt(), g.getDurationMinutes(), venue,
				g.getCapacity(), g.getTotalCost(), g.getPricing(), g.getCurrency(), g.getVisibility(), g.getHostId(), g.getNotes(), participants,
				g.getStatus(), result, fixture, g.getCreatedAt(), g.getCancelledAt(), g.getCancelReason(),
				host == null ? null : host.as(viewer), players, fixtureTeams, g.share(), g.spotsLeft(),
				host != null && viewer != null && g.spotOf(viewer).isPresent() ? host.payoutPhone() : null, invites, friendly, g.getCountry(),
				g.getTimezone(), series, null, viewer != null && g.spotOf(viewer).isPresent() ? payTo(g) : null, asked == 0 ? null : asked);
	}

	/** How the host said to pay for it, or null. */
	static GameResponse.PayToResponse payTo(Game g) {
		var payTo = g.payTo();
		return payTo == null ? null : new GameResponse.PayToResponse(payTo.phone(), payTo.name(), payTo.note());
	}

	private static GameResponse.ResultResponse result(GameResult r, List<ResultVote> votes, UUID viewer, Instant now) {
		var lines = r.getPlayers();
		var keysOn = (Function<String, List<String>>) side -> lines.stream()
			.filter(l -> side.equals(l.side())).map(ResultLine::playerKey).toList();
		var scorers = lines.stream()
			.filter(l -> l.goals() > 0 || l.assists() > 0 || l.points() > 0)
			.map(l -> new GameResponse.Scorer(l.playerKey(), positive(l.goals()), positive(l.assists()), positive(l.points())))
			.toList();
		var sets = r.getSets().stream().map(s -> new GameResponse.SetScore(s.home(), s.away())).toList();
		var absent = keysOn.apply(ResultLine.ABSENT);
		return new GameResponse.ResultResponse(r.getHomeScore(), r.getAwayScore(),
				new GameResponse.Sides(keysOn.apply(ResultLine.HOME), keysOn.apply(ResultLine.AWAY)), scorers, sets.isEmpty() ? null : sets,
				absent.isEmpty() ? null : absent, r.getRecordedBy(), r.getRecordedAt(),
				r.getConfirmations().stream().map(c -> c.userId()).toList(),
				r.getDisputes().stream().map(d -> new GameResponse.Dispute(d.userId(), d.reason(), d.disputedAt())).toList(),
				r.getHomePenalties(), r.getAwayPenalties(), r.tally(votes, viewer, now).map(GameViews::vote).orElse(null));
	}

	private static GameResponse.Vote vote(VoteTally t) {
		return new GameResponse.Vote(t.closesAt(), t.open(), t.cast(), t.eligible(), t.myPick(),
				t.winners().isEmpty() ? null : t.winners(), t.winners().isEmpty() ? null : t.votes());
	}

	private static Integer positive(int value) {
		return value > 0 ? value : null;
	}

	private static GameResponse.Guest guest(Participant p, boolean hostView) {
		if (!p.isGuest()) {
			return null;
		}
		return new GameResponse.Guest(p.getGuestName(), hostView ? p.getGuestPhone() : null, p.getId().toString(), p.getGuestAddedBy(),
				hostView ? p.getGuestEmail() : null, p.isGuestSelfJoined() ? Boolean.TRUE : null);
	}

	private static GameResponse.PlayerResponse player(Participant p, UserSummary user, UUID viewer) {
		if (user == null) {
			return null;
		}
		var shown = user.as(viewer);
		return new GameResponse.PlayerResponse(shown.id().toString(), shown.phone(), shown.name(), shown.handle(), shown.avatar(),
				shown.avatarSeed(), shown.tint(), shown.area(), shown.sports(), shown.roles(), shown.createdAt(), shown.onboarded(),
				shown.payoutPhone(), p.isPaid(), p.getAttended(), null);
	}

	private static GameResponse.PlayerResponse guestPlayer(Participant p, boolean hostView) {
		return new GameResponse.PlayerResponse(p.playerKey(), "", p.getGuestName(), "", null, null, GUEST_TINT, null, List.of(), Map.of(),
				p.getJoinedAt(), false, null, p.isPaid(), p.getAttended(), guest(p, hostView));
	}

}
