package com.playchale.api.games.internal.service;

import java.time.Clock;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.playchale.api.games.api.GameEvents;
import com.playchale.api.games.api.GameResponse;
import com.playchale.api.games.api.SeriesResponse;
import com.playchale.api.games.internal.domain.Game;
import com.playchale.api.games.internal.domain.GameDetails;
import com.playchale.api.games.internal.domain.GameSeries;
import com.playchale.api.games.internal.domain.Participant;
import com.playchale.api.games.internal.domain.SeriesChange;
import com.playchale.api.games.internal.domain.SeriesRule;
import com.playchale.api.games.internal.repository.GameRepository;
import com.playchale.api.games.internal.repository.GameSeriesRepository;
import com.playchale.api.market.Market;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.venues.api.PitchBookings;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repeating games: starting one, opening each next game when the last one ends, and the host's and
 * players' say over it.
 *
 * <p>Opening a game is creating one the ordinary way ({@link GameService}), so a series' games book
 * their pitch, split their cost and take their players exactly as a game the host set up by hand.
 * The series only decides when, and who to ask.
 */
@Service
public class GameSeriesService {

	private static final String NOBODY_CAME = "Nobody else joined the last two games.";

	private static final DateTimeFormatter KICK_OFF = DateTimeFormatter.ofPattern("HH:mm");

	private final GameSeriesRepository series;

	private final GameRepository games;

	private final GameService gameService;

	private final GameViews views;

	private final PitchBookings pitches;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	GameSeriesService(GameSeriesRepository series, GameRepository games, GameService gameService, GameViews views, PitchBookings pitches,
			ApplicationEventPublisher events, Clock clock) {
		this.series = series;
		this.games = games;
		this.gameService = gameService;
		this.views = views;
		this.pitches = pitches;
		this.events = events;
		this.clock = clock;
	}

	/** games.create with "repeats": the first game, and the series that carries it on. */
	@Transactional
	public GameResponse start(GameDetails details, String timezone, String frequency, Integer weekOfMonth, UUID host) {
		var first = gameService.createFirstOfSeries(details, timezone, host);
		begin(first, frequency, weekOfMonth);
		return views.of(first, host);
	}

	/**
	 * series.startFrom: a game the host already has, repeating from now on. One that's already over
	 * opens its next game straight away, and that's the game returned.
	 */
	@Transactional
	public GameResponse startFrom(UUID gameId, String frequency, Integer weekOfMonth, UUID host) {
		var game = games.lockById(gameId).orElseThrow(GameService::notFound);
		if (!game.isHost(host)) {
			throw BusinessException.conflict("Only the host can do that.");
		}
		var started = begin(game, frequency, weekOfMonth);
		return views.of(open(started, false).orElse(game), host);
	}

	private GameSeries begin(Game first, String frequency, Integer weekOfMonth) {
		var rule = SeriesRule.of(frequency, weekOfMonth, first.getStartsAt(), first.zone());
		// Flushed now: the game takes the series' id, and the database checks both ways.
		var started = series.saveAndFlush(new GameSeries(first, rule));
		first.belongTo(started.getId());
		events.publishEvent(new GameEvents.SeriesUpdated(started.getId(), first.getHostId(), "started"));
		return started;
	}

	/** The opener's work for one series: its next game, if it's due. Locked, so however it's run, a date opens once. */
	@Transactional
	public Optional<UUID> openIfDue(UUID seriesId) {
		var found = series.lockById(seriesId);
		if (found.isEmpty() || !found.get().isDue(clock.instant())) {
			return Optional.empty();
		}
		return open(found.get(), true).map(Game::getId);
	}

	/**
	 * After opening failed in a way nothing checked for (the pitch booked in the same second, say): that
	 * date is skipped and the host told, rather than the opener trying it again every few minutes forever.
	 */
	@Transactional
	public void skipAfterFailure(UUID seriesId, String reason) {
		var found = series.lockById(seriesId);
		var now = clock.instant();
		if (found.isEmpty() || !found.get().isDue(now)) {
			return;
		}
		var s = found.get();
		var startsAt = s.nextDate(now);
		s.missed(startsAt);
		events.publishEvent(new GameEvents.SeriesDateMissed(s.getId(), s.getHostId(), s.getTitle(), startsAt, s.getCountry(), s.getTimezone(),
				reason));
	}

	/**
	 * Opens the series' next game, if it's due: the first of its days at least a few hours away, on its
	 * pitch if that's free, with the last game's players invited. Pauses instead when the last two had
	 * nobody but the host ({@code mayPause}: not straight after the host restarted it).
	 */
	private Optional<Game> open(GameSeries s, boolean mayPause) {
		var now = clock.instant();
		if (!s.isDue(now)) {
			return Optional.empty();
		}
		var last = s.getLastGameId() == null ? null : games.findById(s.getLastGameId()).orElse(null);
		if (s.countLastGame(last) && mayPause) {
			s.pause(NOBODY_CAME);
			events.publishEvent(new GameEvents.SeriesPaused(s.getId(), s.getHostId(), s.getTitle(), NOBODY_CAME));
			return Optional.empty();
		}
		var startsAt = s.nextDate(now);
		var details = s.detailsAt(startsAt);
		var problem = problemWithPitch(details);
		if (problem.isPresent()) {
			s.missed(startsAt);
			events.publishEvent(new GameEvents.SeriesDateMissed(s.getId(), s.getHostId(), s.getTitle(), startsAt, s.getCountry(),
					s.getTimezone(), problem.get()));
			return Optional.empty();
		}
		var game = gameService.createForSeries(details, s.getCountry(), s.getTimezone(), s.getHostId(), s.getId());
		var invited = gameService.inviteRegulars(game, regulars(s, last));
		s.opened(game);
		events.publishEvent(new GameEvents.SeriesGameOpened(GameService.info(game), s.getId(), invited));
		return Optional.of(game);
	}

	/**
	 * Who's asked to the next game: everyone who had a spot in the last one (a called-off one too: they
	 * were coming), but not guests without an account, the host, or anyone who asked not to be.
	 */
	private List<UUID> regulars(GameSeries s, Game last) {
		if (last == null) {
			return List.of();
		}
		var optedOut = Set.copyOf(series.optedOut(s.getId()));
		return last.getParticipants().stream()
			.map(Participant::getUserId)
			.filter(Objects::nonNull)
			.filter(id -> !id.equals(s.getHostId()) && !optedOut.contains(id))
			.toList();
	}

	/** Why the series' pitch can't be had for this game, if it can't: booked, or the venue closed then. */
	private Optional<String> problemWithPitch(GameDetails details) {
		if (details.venueId() == null || details.pitchId() == null) {
			return Optional.empty();
		}
		return pitches.problemForGame(details.venueId(), details.pitchId(), details.startsAt(),
				details.startsAt().plus(Duration.ofMinutes(details.durationMinutes())), null);
	}

	/**
	 * series.change: from the next game opened; one already open stays as it is. A time its pitch is
	 * booked at is refused now, rather than quietly skipped later.
	 */
	@Transactional
	public SeriesResponse change(UUID seriesId, SeriesChange change, UUID host) {
		var s = hosted(seriesId, host);
		var open = lastGame(s);
		var now = clock.instant();
		s.change(change, open, now);
		if (s.isActive()) {
			var problem = problemWithPitch(s.detailsAt(s.nextDate(now)));
			if (problem.isPresent()) {
				throw BusinessException.conflict(problem.get());
			}
		}
		events.publishEvent(new GameEvents.SeriesUpdated(s.getId(), host, "changed"));
		return response(s, open);
	}

	/** series.stop: no more games. The one still to come, if any, stays: the host calls it off separately if it's off too. */
	@Transactional
	public SeriesResponse stop(UUID seriesId, UUID host) {
		var s = hosted(seriesId, host);
		s.stop(clock.instant());
		events.publishEvent(new GameEvents.SeriesUpdated(s.getId(), host, "stopped"));
		return response(s, lastGame(s));
	}

	/** series.restart: back on after a pause or a stop. With no game still to come, the next one opens now. */
	@Transactional
	public SeriesResponse restart(UUID seriesId, UUID host) {
		var s = hosted(seriesId, host);
		s.restart(lastGame(s), clock.instant());
		events.publishEvent(new GameEvents.SeriesUpdated(s.getId(), host, "restarted"));
		open(s, false);
		return response(s, lastGame(s));
	}

	/**
	 * series.optOut: a player asking not to be invited to its games any more ({@code out}), or to be
	 * again. They can still join any of them.
	 */
	@Transactional
	public void optOut(UUID seriesId, boolean out, UUID me) {
		var s = series.findById(seriesId).orElseThrow(GameSeriesService::notFound);
		if (s.isHost(me)) {
			throw BusinessException.conflict("You host this game, so there’s nobody to invite you.");
		}
		var changed = out ? series.optOut(seriesId, me, clock.instant()) : series.optIn(seriesId, me);
		if (changed > 0) {
			events.publishEvent(new GameEvents.SeriesOptOut(seriesId, me, out));
		}
	}

	/** series.mine: the host's repeating games, running or not, newest first. */
	@Transactional(readOnly = true)
	public List<SeriesResponse> mine(UUID host) {
		var all = series.findByHostIdOrderByCreatedAtDesc(host);
		var lastGames = games.findAllById(all.stream().map(GameSeries::getLastGameId).filter(Objects::nonNull).toList()).stream()
			.collect(Collectors.toMap(Game::getId, Function.identity()));
		return all.stream().map(s -> response(s, s.getLastGameId() == null ? null : lastGames.get(s.getLastGameId()))).toList();
	}

	/** Its latest game moved with its booking: the next opens when it now ends, not when it would have. */
	@EventListener
	void on(GameEvents.GameMoved e) {
		series.findByLastGameId(e.game().gameId()).ifPresent(s -> games.findById(e.game().gameId()).ifPresent(s::lastGameMoved));
	}

	/** A player closing their account: their series stop, and their opt-outs go with their invites. */
	void accountClosed(UUID userId) {
		var now = clock.instant();
		series.findByHostIdOrderByCreatedAtDesc(userId).stream().filter(s -> !GameSeries.STOPPED.equals(s.getStatus()))
			.forEach(s -> s.stop(now));
		series.forgetOptOuts(userId);
	}

	private Game lastGame(GameSeries s) {
		return s.getLastGameId() == null ? null : games.findById(s.getLastGameId()).orElse(null);
	}

	private GameSeries hosted(UUID seriesId, UUID host) {
		var s = series.lockById(seriesId).orElseThrow(GameSeriesService::notFound);
		if (!s.isHost(host)) {
			throw BusinessException.conflict("Only the host can do that.");
		}
		return s;
	}

	private static SeriesResponse response(GameSeries s, Game last) {
		return new SeriesResponse(s.getId(), s.getTitle(), s.getSport(), s.getFormat(), s.getVenueKind(), s.getVenueId(), s.getPitchId(),
				s.getVenueName(), s.getVenueArea(), s.getDurationMinutes(), s.getCapacity(), s.getTotalCost(), s.getPricing(),
				Market.get(s.getCountry()).currency(), s.getVisibility(), s.getNotes(), s.getCountry(), s.getTimezone(), s.getFrequency(),
				s.getWeekday(), s.getWeekOfMonth(), s.getKickOff().format(KICK_OFF), s.getStatus(), s.getPausedReason(), s.getNextStartsAt(),
				s.getOpensAt(), last == null ? null : new SeriesResponse.LastGame(last.getId(), last.getStartsAt(), last.getStatus()));
	}

	private static BusinessException notFound() {
		return BusinessException.notFound("That repeating game no longer exists.");
	}

}
