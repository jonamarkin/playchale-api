package com.playchale.api.games.internal.service;

import com.playchale.api.games.api.GameResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import com.playchale.api.games.api.GameEvents;
import com.playchale.api.games.internal.domain.Game;
import com.playchale.api.games.internal.domain.GameDetails;
import com.playchale.api.games.internal.domain.GameInvite;
import com.playchale.api.games.internal.domain.InviteId;
import com.playchale.api.games.internal.domain.Participant;
import com.playchale.api.games.internal.repository.GameInviteRepository;
import com.playchale.api.games.internal.repository.GameRepository;
import com.playchale.api.market.Market;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.teams.api.TeamCard;
import com.playchale.api.teams.api.TeamDirectory;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.users.api.UserSummary;
import com.playchale.api.venues.api.GameBookingMoved;
import com.playchale.api.venues.api.PitchBookings;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Games: finding them, hosting them, and everything that happens to a roster before kick-off.
 * Every change locks the game first, and publishes a {@link GameEvents} event for whoever should
 * hear about it.
 */
@Service
public class GameService {

	/** Discover and "my games" show at most this many. */
	private static final int LIST_LIMIT = 100;

	private static final SecureRandom random = new SecureRandom();

	private final GameRepository games;

	private final GameInviteRepository invites;

	private final GameViews views;

	private final UserDirectory users;

	private final PitchBookings pitches;

	private final TeamDirectory teams;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	GameService(GameRepository games, GameInviteRepository invites, GameViews views, UserDirectory users, PitchBookings pitches,
			TeamDirectory teams, ApplicationEventPublisher events, Clock clock) {
		this.games = games;
		this.invites = invites;
		this.views = views;
		this.users = users;
		this.pitches = pitches;
		this.teams = teams;
		this.events = events;
		this.clock = clock;
	}

	/** games.list: upcoming games still on that the viewer may see, soonest first. */
	@Transactional(readOnly = true)
	public List<GameResponse> list(GameFilters filters, UUID viewer) {
		var now = clock.instant();
		var zone = viewersZone(filters.zone());
		var country = filters.country() == null || !Market.exists(filters.country()) ? "" : filters.country().strip().toUpperCase(Locale.ROOT);
		var sport = filters.sport() == null || filters.sport().equals("all") ? "" : filters.sport();
		var query = filters.query() == null ? "" : filters.query().strip().toLowerCase(Locale.ROOT);
		Instant from = now;
		Instant to = now.plus(Duration.ofDays(3650));
		var today = now.atZone(zone).truncatedTo(ChronoUnit.DAYS);
		switch (filters.when() == null ? "any" : filters.when()) {
			case "today" -> to = today.plusDays(1).toInstant();
			case "tomorrow" -> {
				from = today.plusDays(1).toInstant();
				to = today.plusDays(2).toInstant();
			}
			case "weekend" -> {
				// The coming Saturday to Monday, or the rest of it if it's already the weekend.
				var day = today.getDayOfWeek();
				var saturday = day == DayOfWeek.SUNDAY ? today.minusDays(1) : today.plusDays((DayOfWeek.SATURDAY.getValue() - day.getValue() + 7) % 7);
				from = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY ? now : saturday.toInstant();
				to = saturday.plusDays(2).toInstant();
			}
			default -> {
			}
		}
		var found = games.discover(now, viewer, sport, country, from, to, "%" + query + "%", Limit.of(LIST_LIMIT));
		return views.of(found, viewer);
	}

	/** The viewer's clock, for what "today" means: the zone their app sent, else Ghana's. */
	private static ZoneId viewersZone(String zone) {
		try {
			return zone == null || zone.isBlank() ? Market.get(Market.DEFAULT).zone() : ZoneId.of(zone.strip());
		}
		catch (DateTimeException e) {
			return Market.get(Market.DEFAULT).zone();
		}
	}

	/** games.mine: games the player hosts or has a spot in. */
	@Transactional(readOnly = true)
	public List<GameResponse> mine(UUID me) {
		return views.of(games.involving(me, Limit.of(LIST_LIMIT * 2)), me);
	}

	/** games.invitations: games the player is invited to and hasn't answered, still to come, soonest first. */
	@Transactional(readOnly = true)
	public List<GameResponse> invitations(UUID me) {
		var ids = invites.waitingFor(me, clock.instant());
		var byId = games.findAllById(ids).stream().collect(Collectors.toMap(Game::getId, g -> g));
		return views.of(ids.stream().map(byId::get).filter(Objects::nonNull).toList(), me);
	}

	/** games.get. Private games are open to anyone with the link, like a WhatsApp group invite. */
	@Transactional(readOnly = true)
	public GameResponse get(UUID id, UUID viewer) {
		return views.of(games.findById(id).orElseThrow(GameService::notFound), viewer);
	}

	/** games.create. At a partner venue with a pitch picked, the pitch is booked in the same transaction. */
	@Transactional
	public GameResponse create(GameDetails details, UUID host) {
		return create(details, null, null, null, host);
	}

	/**
	 * Where a game is played: its country (money, phone numbers) and its local time. At a partner
	 * venue, the venue's; anywhere else, the host's country and the timezone their app sends.
	 */
	private record Place(String country, String timezone) {
	}

	/**
	 * games.create with two teams: a friendly. The host captains the home team, whose players are
	 * invited now; the away team's captain is challenged, and their players are asked once they accept.
	 * Without teams, an ordinary game.
	 */
	@Transactional
	public GameResponse create(GameDetails details, UUID homeTeamId, UUID awayTeamId, UUID host) {
		return create(details, null, homeTeamId, awayTeamId, host);
	}

	/** As above. {@code timezone} is where a game at a typed-in place is played, as the host's app sends it (IANA). */
	@Transactional
	public GameResponse create(GameDetails details, String timezone, UUID homeTeamId, UUID awayTeamId, UUID host) {
		var place = new Place(countryOf(host), timezone);
		if (homeTeamId == null && awayTeamId == null) {
			return views.of(createGame(details, place, host), host);
		}
		return views.of(createFriendly(details, place, homeTeamId, awayTeamId, host), host);
	}

	/** games.repeat: the same game a week later, on the same pitch if it's free. A friendly challenges the same team again. */
	@Transactional
	public GameResponse repeat(UUID gameId, UUID host) {
		var game = hosted(gameId, host);
		var details = game.details().weekLater();
		var place = new Place(game.getCountry(), game.getTimezone());
		return views.of(game.isFriendly() ? createFriendly(details, place, game.getHomeTeamId(), game.getAwayTeamId(), host)
				: createGame(details, place, host), host);
	}

	/** games.answerChallenge: the away team's captain. Yes puts the team in (and them, if they play for it) and asks its players. */
	@Transactional
	public GameResponse answerChallenge(UUID gameId, boolean accept, UUID me) {
		var game = locked(gameId);
		if (!game.awaitsOpponent()) {
			throw BusinessException.notFound("That challenge has already been answered.");
		}
		var away = teams.find(game.getAwayTeamId()).orElseThrow(() -> BusinessException.notFound("That team doesn’t exist any more."));
		if (!away.captainId().equals(me)) {
			throw BusinessException.conflict("Only %s can answer for %s.".formatted(firstName(away.captainId()), away.name()));
		}
		var home = teams.find(game.getHomeTeamId()).map(TeamCard::name).orElse("The other team");
		var now = clock.instant();
		game.answerChallenge(accept, now);
		events.publishEvent(new GameEvents.ChallengeAnswered(info(game), home, away.name(), me, accept));
		if (accept) {
			if (away.memberIds().contains(me) && game.spotOf(me).isEmpty() && game.spotsLeft() > 0) {
				game.join(me, away.id(), now);
				events.publishEvent(new GameEvents.PlayerJoined(info(game), me, game.getCapacity() - game.spotsLeft(), game.getCapacity(), false));
			}
			if (game.spotsLeft() > 0) {
				ask(game, away.memberIds(), away.id(), away.name(), me);
			}
		}
		return views.of(game, me);
	}

	/** games.join */
	@Transactional
	public GameResponse join(UUID gameId, UUID me) {
		var game = locked(gameId);
		if (game.spotOf(me).isEmpty()) {
			game.join(me, sideFor(game, me), clock.instant());
			events.publishEvent(new GameEvents.PlayerJoined(info(game), me, game.getCapacity() - game.spotsLeft(), game.getCapacity(), false));
		}
		// Joining is saying yes to an invite, however they got here.
		invites.findById(new InviteId(gameId, me)).filter(i -> !i.isAccepted()).ifPresent(i -> i.accept(clock.instant()));
		return views.of(game, me);
	}

	/** games.leave */
	@Transactional
	public GameResponse leave(UUID gameId, UUID me) {
		var game = locked(gameId);
		var wasIn = game.spotOf(me).isPresent();
		game.leave(me);
		// Out after saying yes: the host sees they can't make it after all.
		if (wasIn) {
			invites.findById(new InviteId(gameId, me)).filter(GameInvite::isAccepted).ifPresent(i -> i.decline(clock.instant()));
		}
		return views.of(game, me);
	}

	/** games.removePlayer: host only, before kick-off. {@code playerKey} is a player's ID or "guest:<token>". */
	@Transactional
	public GameResponse removePlayer(UUID gameId, String playerKey, UUID host) {
		var game = hosted(gameId, host);
		var spot = game.spotByKey(playerKey).orElseThrow(() -> BusinessException.notFound("That player isn’t in this game."));
		var name = spot.isGuest() ? spot.getGuestName() : firstName(spot.getUserId());
		if (game.remove(spot, host, name, clock.instant())) {
			events.publishEvent(new GameEvents.PlayerRemoved(info(game), spot.getUserId()));
			// Taken off by the host: no longer invited either.
			invites.findById(new InviteId(gameId, spot.getUserId())).ifPresent(invites::delete);
		}
		return views.of(game, host);
	}

	/** games.cancel: host only. Frees the pitch and tells everyone. */
	@Transactional
	public GameResponse cancel(UUID gameId, String reason, UUID host) {
		var game = hosted(gameId, host);
		var paidNames = game.paidByOthers().stream()
			.map(p -> p.isGuest() ? p.getGuestName() : firstName(p.getUserId()))
			.toList();
		game.cancel(reason, paidNames, clock.instant());

		UUID venueOwner = null;
		if (game.getPitchId() != null) {
			pitches.releaseForGame(game.getId());
			venueOwner = pitches.findVenue(game.getVenueId()).map(v -> v.ownerId()).orElse(null);
		}
		var players = game.getParticipants().stream()
			.map(Participant::getUserId)
			.filter(id -> id != null && !id.equals(host))
			.toList();
		events.publishEvent(new GameEvents.GameCalledOff(info(game), players, game.getCancelReason(), game.getVenueId(), venueOwner,
				game.getPitchName()));
		return views.of(game, host);
	}

	/**
	 * games.invite: host only. Invited players get a notification, not a spot: each accepts (and is
	 * in) or declines. Inviting someone again asks afresh, as a nudge.
	 */
	@Transactional
	public int invite(UUID gameId, Collection<UUID> userIds, UUID host) {
		var game = invitable(gameId, host);
		return ask(game, userIds.stream().distinct().toList(), null, null, host);
	}

	/** games.inviteTeam: host only, for a team they're in. Its members not in the game yet are each invited. */
	@Transactional
	public int inviteTeam(UUID gameId, UUID teamId, UUID host) {
		var game = invitable(gameId, host);
		var team = teams.find(teamId).orElseThrow(() -> BusinessException.notFound("That team doesn’t exist any more."));
		if (!team.captainId().equals(host) && !team.memberIds().contains(host)) {
			throw BusinessException.conflict("You can only invite a team you’re in.");
		}
		var invited = ask(game, team.memberIds(), team.id(), team.name(), host);
		if (invited == 0) {
			throw BusinessException.conflict("Everyone in %s is already in the game.".formatted(team.name()));
		}
		return invited;
	}

	/** games.answerInvite: yes is joining (the usual rules apply); no is recorded and the host is told. */
	@Transactional
	public GameResponse answerInvite(UUID gameId, boolean accept, UUID me) {
		var game = locked(gameId);
		var invite = invites.findById(new InviteId(gameId, me))
			.orElseThrow(() -> BusinessException.notFound("You don’t have an invite to this game."));
		var now = clock.instant();
		if (accept) {
			if (game.spotOf(me).isEmpty()) {
				game.join(me, sideFor(game, me), now);
				events.publishEvent(new GameEvents.PlayerJoined(info(game), me, game.getCapacity() - game.spotsLeft(), game.getCapacity(), false));
			}
			invite.accept(now);
		}
		else {
			if (game.spotOf(me).isPresent()) {
				throw BusinessException.conflict("You’re in this game. Leave it instead.");
			}
			if (invite.isPending()) {
				events.publishEvent(new GameEvents.InviteDeclined(info(game), me));
			}
			invite.decline(now);
		}
		return views.of(game, me);
	}

	/** What the host gets back from holding a spot: the game, and the token for the claim link. */
	public record GuestAdded(GameResponse game, String token) {
	}

	/** games.addGuest: host only. The claim token is returned once, here, and only its hash is kept. */
	@Transactional
	public GuestAdded addGuest(UUID gameId, String name, String phone, UUID host) {
		var game = hosted(gameId, host);
		String e164 = null;
		if (phone != null && !phone.isBlank()) {
			e164 = game.market().normalisePhone(phone)
				.orElseThrow(() -> BusinessException.invalid("That number doesn’t look right. Leave it blank if you’re not sure."));
			var number = e164;
			var players = users.findAll(game.getParticipants().stream().map(Participant::getUserId).filter(Objects::nonNull).toList());
			var taken = game.getParticipants().stream().anyMatch(p -> number.equals(p.isGuest() ? p.getGuestPhone()
					: players.containsKey(p.getUserId()) ? players.get(p.getUserId()).phone() : null));
			if (taken) {
				throw BusinessException.conflict("Someone with that number already has a spot.");
			}
		}
		var token = newToken();
		game.holdForGuest(name, e164, hash(token), host, clock.instant());
		return new GuestAdded(views.of(games.saveAndFlush(game), host), token);
	}

	/** games.claimSpot: the spot the host held becomes the signed-in player's. */
	@Transactional
	public GameResponse claimSpot(UUID gameId, String token, UUID me) {
		var game = locked(gameId);
		var spot = game.guestSpotByClaimHash(hash(token == null ? "" : token))
			.orElseThrow(() -> BusinessException.notFound("That invite has already been used, or the host removed the spot."));
		var phone = users.find(me).map(UserSummary::phone).orElse(null);
		game.claim(spot, me, phone);
		game.playFor(me, sideFor(game, me));
		events.publishEvent(new GameEvents.PlayerJoined(info(game), me, game.getCapacity() - game.spotsLeft(), game.getCapacity(), true));
		invites.findById(new InviteId(gameId, me)).filter(i -> !i.isAccepted()).ifPresent(i -> i.accept(clock.instant()));
		return views.of(game, me);
	}

	/** games.remind: host only. Returns how many were reminded. */
	@Transactional
	public int remind(UUID gameId, Collection<UUID> only, UUID host) {
		var game = hosted(gameId, host);
		var unpaid = game.unpaid(only);
		game.reminded(unpaid, clock.instant());
		if (!unpaid.isEmpty()) {
			events.publishEvent(new GameEvents.PaymentReminded(info(game), unpaid.stream().map(Participant::getUserId).toList(),
					share(game), game.getCurrency(), Game.PER_PLAYER.equals(game.getPricing())));
		}
		return unpaid.size();
	}

	/** games.markPaidCash: host only. {@code playerKey} is a player's ID or "guest:<token>". */
	@Transactional
	public GameResponse markPaidCash(UUID gameId, String playerKey, UUID host) {
		var game = hosted(gameId, host);
		var spot = game.spotByKey(playerKey).orElseThrow(() -> BusinessException.notFound("That player isn’t in this game."));
		game.paidInCash(spot);
		events.publishEvent(new GameEvents.CashShareCollected(info(game), spot.getUserId(), share(game), game.getCurrency()));
		return views.of(game, host);
	}

	/** A new game where it's played: at a partner venue, the venue's country and time; anywhere else, {@code place}. */
	private Game createGame(GameDetails details, Place place, UUID host) {
		Game game;
		if (Game.LISTED.equals(details.venueKind())) {
			if (details.venueId() == null) {
				throw BusinessException.invalid("Pick a venue.");
			}
			var venue = pitches.findVenue(details.venueId()).orElseThrow(() -> BusinessException.invalid("That venue could not be found."));
			game = new Game(details, host, Market.get(venue.country()), clock.instant());
			game.keepTime(venue.timezone());
			game.playAt(venue.id(), venue.name(), venue.area(), null, null);
		}
		else {
			game = new Game(details, host, Market.get(place.country()), clock.instant());
			game.keepTime(place.timezone());
			game.playAt(details.venueName(), details.venueArea(), details.venueMapUrl());
		}
		games.save(game);

		if (details.pitchId() != null && game.getVenueId() != null) {
			var booked = pitches.bookForGame(game.getVenueId(), details.pitchId(), game.getStartsAt(), game.endsAt(), game.getId(), host);
			game.playAt(booked.venueId(), booked.venueName(), booked.venueArea(), booked.pitchId(), booked.pitchName());
			game.splitPitchCost();
			events.publishEvent(new GameEvents.PitchBooked(info(game), booked.venueId(), booked.ownerId(), booked.pitchName()));
		}
		return game;
	}

	/** A game the host can still invite people to: on, not kicked off, with a spot to offer. */
	private Game invitable(UUID gameId, UUID host) {
		var game = hosted(gameId, host);
		if (game.isCancelled()) {
			throw BusinessException.conflict("This game was called off.");
		}
		if (game.hasStarted(clock.instant())) {
			throw BusinessException.conflict("This game has already kicked off.");
		}
		if (game.spotsLeft() == 0) {
			throw BusinessException.conflict("The game is full. There’s no spot to offer.");
		}
		return game;
	}

	/**
	 * Invites (or asks again) real players who aren't the host and aren't in the game yet, and tells
	 * them. Returns how many. {@code by} is the host, or a friendly's away captain asking their players.
	 */
	private int ask(Game game, Collection<UUID> userIds, UUID teamId, String teamName, UUID by) {
		var candidates = userIds.stream().filter(id -> !id.equals(game.getHostId()) && game.spotOf(id).isEmpty()).toList();
		var real = users.findAll(candidates).keySet();
		var invited = candidates.stream().filter(real::contains).distinct().toList();
		var now = clock.instant();
		var existing = invites.findAllById(invited.stream().map(u -> new InviteId(game.getId(), u)).toList()).stream()
			.collect(Collectors.toMap(GameInvite::getUserId, i -> i));
		for (var userId : invited) {
			var invite = existing.get(userId);
			if (invite == null) {
				invites.save(new GameInvite(game.getId(), userId, teamId, by, now));
			}
			else {
				invite.ask(teamId, by, now);
			}
		}
		if (!invited.isEmpty()) {
			events.publishEvent(new GameEvents.PlayersInvited(info(game), by, invited, share(game), game.getCurrency(), teamName));
		}
		return invited.size();
	}

	/**
	 * Which side someone plays for in a friendly: the team they were invited with, else the team
	 * they're in (the away team only once it has accepted). Null outside friendlies, or for anyone
	 * in neither team: the host puts them on a side on the day.
	 */
	private UUID sideFor(Game game, UUID userId) {
		if (!game.isFriendly()) {
			return null;
		}
		var invitedWith = invites.findById(new InviteId(game.getId(), userId)).map(GameInvite::getTeamId)
			.filter(t -> t.equals(game.getHomeTeamId()) || (t.equals(game.getAwayTeamId()) && game.opponentIn()));
		if (invitedWith.isPresent()) {
			return invitedWith.get();
		}
		var sides = teams.findAll(List.of(game.getHomeTeamId(), game.getAwayTeamId()));
		var home = sides.get(game.getHomeTeamId());
		if (home != null && (home.memberIds().contains(userId) || home.captainId().equals(userId))) {
			return home.id();
		}
		var away = sides.get(game.getAwayTeamId());
		return away != null && game.opponentIn() && (away.memberIds().contains(userId) || away.captainId().equals(userId)) ? away.id() : null;
	}

	/** A friendly: the host's team at home, the other team challenged (or straight in, when the host captains both). */
	private Game createFriendly(GameDetails details, Place place, UUID homeTeamId, UUID awayTeamId, UUID host) {
		if (homeTeamId == null || awayTeamId == null) {
			throw BusinessException.invalid("Pick your team and the team you’re playing.");
		}
		if (homeTeamId.equals(awayTeamId)) {
			throw BusinessException.invalid("A team can’t play itself. Pick another team to play.");
		}
		var home = teams.find(homeTeamId).orElseThrow(() -> BusinessException.notFound("That team doesn’t exist any more."));
		var away = teams.find(awayTeamId).orElseThrow(() -> BusinessException.notFound("The team you’re playing doesn’t exist any more."));
		if (!home.captainId().equals(host)) {
			throw BusinessException.conflict("Only %s can set up a game for %s.".formatted(firstName(home.captainId()), home.name()));
		}
		var named = details.title() == null || details.title().isBlank() ? details.withTitle("%s vs %s".formatted(home.name(), away.name())) : details;
		var game = createGame(named, place, host);
		var accepted = away.captainId().equals(host);
		game.challenge(home.id(), away.id(), accepted);
		ask(game, home.memberIds(), home.id(), home.name(), host);
		if (accepted) {
			ask(game, away.memberIds(), away.id(), away.name(), host);
		}
		else {
			events.publishEvent(new GameEvents.ChallengeSent(info(game), home.name(), away.name(), away.captainId()));
		}
		return game;
	}

	private Game hosted(UUID gameId, UUID userId) {
		var game = locked(gameId);
		if (!game.isHost(userId)) {
			throw BusinessException.conflict("Only the host can do that.");
		}
		return game;
	}

	private Game locked(UUID gameId) {
		return games.lockById(gameId).orElseThrow(GameService::notFound);
	}

	private String firstName(UUID userId) {
		return users.find(userId).map(u -> u.name().split(" ")[0]).filter(n -> !n.isBlank()).orElse("A player");
	}

	private static long share(Game game) {
		return game.share();
	}

	/** The host's country: where a game at a typed-in place is, unless it says otherwise. */
	private String countryOf(UUID userId) {
		return users.find(userId).map(UserSummary::country).filter(Market::exists).orElse(Market.DEFAULT);
	}

	/**
	 * The venue moved this game's pitch booking (venues module, in the same transaction): the game
	 * moves with it, and everyone in it is told.
	 */
	@EventListener
	void on(GameBookingMoved e) {
		var game = locked(e.gameId());
		var from = game.getStartsAt();
		var fromPitch = game.getPitchName();
		game.movedByVenue(e.pitchId(), e.pitchName(), e.startsAt(), clock.instant());
		var players = game.getParticipants().stream().map(Participant::getUserId).filter(Objects::nonNull).toList();
		events.publishEvent(new GameEvents.GameMoved(info(game), from, fromPitch, e.pitchName(), players));
	}

	static GameEvents.GameInfo info(Game game) {
		return new GameEvents.GameInfo(game.getId(), game.getTitle(), game.getStartsAt(), game.getHostId(), game.getVenueName(), game.getCountry(),
				game.getTimezone());
	}

	static BusinessException notFound() {
		return BusinessException.notFound("This game no longer exists.");
	}

	private static String newToken() {
		var bytes = new byte[16];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String hash(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("SHA-256 is always available", e);
		}
	}

}
