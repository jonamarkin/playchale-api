package com.playchale.api.games.internal.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.playchale.api.games.internal.domain.Game;
import com.playchale.api.games.internal.domain.GameResult;
import com.playchale.api.games.internal.domain.Participant;
import com.playchale.api.games.internal.domain.ResultLine;
import com.playchale.api.games.internal.repository.GameResultRepository;
import com.playchale.api.market.Market;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.users.api.UserSummary;
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

	GameViews(UserDirectory users, GameResultRepository results) {
		this.users = users;
		this.results = results;
	}

	GameResponse of(Game game, UUID viewer) {
		return of(List.of(game), viewer).getFirst();
	}

	List<GameResponse> of(Collection<Game> games, UUID viewer) {
		var ids = games.stream()
			.flatMap(g -> Stream.concat(Stream.of(g.getHostId()), g.getParticipants().stream().map(Participant::getUserId)))
			.filter(Objects::nonNull)
			.distinct()
			.toList();
		var people = users.findAll(ids);
		var played = games.stream().filter(g -> Game.COMPLETED.equals(g.getStatus())).map(Game::getId).toList();
		var resultsByGame = played.isEmpty() ? Map.<UUID, GameResult>of()
				: results.findAllById(played).stream().collect(Collectors.toMap(GameResult::getGameId, r -> r));
		return games.stream().map(g -> view(g, viewer, people, resultsByGame.get(g.getId()))).toList();
	}

	private GameResponse view(Game g, UUID viewer, Map<UUID, UserSummary> people, GameResult result) {
		var market = Market.get(Market.DEFAULT);
		var hostView = g.isHost(viewer);
		var venue = new GameResponse.VenueRef(g.getVenueKind(), g.getVenueId(), g.getVenueName(), g.getVenueArea(), g.getPitchId(),
				g.getPitchName());
		var participants = g.getParticipants().stream()
			.map(p -> new GameResponse.ParticipantResponse(p.isGuest() ? "" : p.getUserId().toString(), p.getJoinedAt(), p.isPaid(),
					p.getPaymentId(), p.getPaidVia(), p.getRemindedAt(), guest(p, hostView)))
			.toList();
		var players = g.getParticipants().stream()
			.map(p -> p.isGuest() ? guestPlayer(p, hostView) : player(p, people.get(p.getUserId()), viewer))
			.filter(Objects::nonNull)
			.toList();
		var host = people.get(g.getHostId());
		return new GameResponse(g.getId(), g.getSport(), g.getFormat(), g.getTitle(), g.getStartsAt(), g.getDurationMinutes(), venue,
				g.getCapacity(), g.getTotalCost(), g.getCurrency(), g.getVisibility(), g.getHostId(), g.getNotes(), participants,
				g.getStatus(), result == null ? null : result(result), g.getCreatedAt(), g.getCancelledAt(), g.getCancelReason(), host == null ? null : host.as(viewer), players,
				market.shareOf(g.getTotalCost(), g.getCapacity()), g.spotsLeft());
	}

	private static GameResponse.ResultResponse result(GameResult r) {
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
				r.getDisputes().stream().map(d -> new GameResponse.Dispute(d.userId(), d.reason(), d.disputedAt())).toList());
	}

	private static Integer positive(int value) {
		return value > 0 ? value : null;
	}

	private static GameResponse.Guest guest(Participant p, boolean hostView) {
		if (!p.isGuest()) {
			return null;
		}
		return new GameResponse.Guest(p.getGuestName(), hostView ? p.getGuestPhone() : null, p.getId().toString(), p.getGuestAddedBy());
	}

	private static GameResponse.PlayerResponse player(Participant p, UserSummary user, UUID viewer) {
		if (user == null) {
			return null;
		}
		var shown = user.as(viewer);
		return new GameResponse.PlayerResponse(shown.id().toString(), shown.phone(), shown.name(), shown.handle(), shown.avatar(),
				shown.tint(), shown.area(), shown.sports(), shown.position(), shown.createdAt(), shown.onboarded(),
				shown.payoutPhone(), p.isPaid(), null);
	}

	private static GameResponse.PlayerResponse guestPlayer(Participant p, boolean hostView) {
		return new GameResponse.PlayerResponse(p.playerKey(), "", p.getGuestName(), "", null, GUEST_TINT, null, List.of(), null,
				p.getJoinedAt(), false, null, p.isPaid(), guest(p, hostView));
	}

}
