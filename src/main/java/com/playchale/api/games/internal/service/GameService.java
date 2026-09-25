package com.playchale.api.games.internal.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import com.playchale.api.games.api.GameEvents;
import com.playchale.api.games.internal.domain.Game;
import com.playchale.api.games.internal.domain.GameDetails;
import com.playchale.api.games.internal.domain.Participant;
import com.playchale.api.games.internal.repository.GameRepository;
import com.playchale.api.market.Market;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.users.api.UserSummary;
import com.playchale.api.venues.api.PitchBookings;
import org.springframework.context.ApplicationEventPublisher;
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

	private final GameViews views;

	private final UserDirectory users;

	private final PitchBookings pitches;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	GameService(GameRepository games, GameViews views, UserDirectory users, PitchBookings pitches,
			ApplicationEventPublisher events, Clock clock) {
		this.games = games;
		this.views = views;
		this.users = users;
		this.pitches = pitches;
		this.events = events;
		this.clock = clock;
	}

	/** games.list: upcoming games still on that the viewer may see, soonest first. */
	@Transactional(readOnly = true)
	public List<GameResponse> list(GameFilters filters, UUID viewer) {
		var now = clock.instant();
		var market = Market.get(Market.DEFAULT);
		var sport = filters.sport() == null || filters.sport().equals("all") ? "" : filters.sport();
		var query = filters.query() == null ? "" : filters.query().strip().toLowerCase(Locale.ROOT);
		Instant from = now;
		Instant to = now.plus(Duration.ofDays(3650));
		var today = now.atZone(market.zone()).truncatedTo(ChronoUnit.DAYS);
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
		var found = games.discover(now, viewer, sport, from, to, "%" + query + "%", Limit.of(LIST_LIMIT));
		return views.of(found, viewer);
	}

	/** games.mine: games the player hosts or has a spot in. */
	@Transactional(readOnly = true)
	public List<GameResponse> mine(UUID me) {
		return views.of(games.involving(me, Limit.of(LIST_LIMIT * 2)), me);
	}

	/** games.get. Private games are open to anyone with the link, like a WhatsApp group invite. */
	@Transactional(readOnly = true)
	public GameResponse get(UUID id, UUID viewer) {
		return views.of(games.findById(id).orElseThrow(GameService::notFound), viewer);
	}

	/** games.create. At a partner venue with a pitch picked, the pitch is booked in the same transaction. */
	@Transactional
	public GameResponse create(GameDetails details, UUID host) {
		var game = createGame(details, host);
		return views.of(game, host);
	}

	/** games.repeat: the same game a week later, on the same pitch if it's free. */
	@Transactional
	public GameResponse repeat(UUID gameId, UUID host) {
		var game = hosted(gameId, host);
		return views.of(createGame(game.details().weekLater(), host), host);
	}

	/** games.join */
	@Transactional
	public GameResponse join(UUID gameId, UUID me) {
		var game = locked(gameId);
		if (game.spotOf(me).isEmpty()) {
			game.join(me, clock.instant());
			events.publishEvent(new GameEvents.PlayerJoined(info(game), me, game.getCapacity() - game.spotsLeft(), game.getCapacity(), false));
		}
		return views.of(game, me);
	}

	/** games.leave */
	@Transactional
	public GameResponse leave(UUID gameId, UUID me) {
		var game = locked(gameId);
		game.leave(me);
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

	/** games.invite: host only. Invited players get a notification, not a spot. */
	@Transactional
	public int invite(UUID gameId, Collection<UUID> userIds, UUID host) {
		var game = hosted(gameId, host);
		if (game.isCancelled()) {
			throw BusinessException.conflict("This game was called off.");
		}
		if (game.spotsLeft() == 0) {
			throw BusinessException.conflict("The game is full. There’s no spot to offer.");
		}
		var candidates = userIds.stream().distinct().filter(id -> !id.equals(host) && game.spotOf(id).isEmpty()).toList();
		var invited = List.copyOf(users.findAll(candidates).keySet());
		if (!invited.isEmpty()) {
			events.publishEvent(new GameEvents.PlayersInvited(info(game), invited, share(game), game.getCurrency()));
		}
		return invited.size();
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
			e164 = Market.get(Market.DEFAULT).normalisePhone(phone)
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
		events.publishEvent(new GameEvents.PlayerJoined(info(game), me, game.getCapacity() - game.spotsLeft(), game.getCapacity(), true));
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
					share(game), game.getCurrency()));
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

	private Game createGame(GameDetails details, UUID host) {
		var game = new Game(details, host, Market.get(Market.DEFAULT), clock.instant());
		if (Game.LISTED.equals(details.venueKind())) {
			if (details.venueId() == null) {
				throw BusinessException.invalid("Pick a venue.");
			}
			var venue = pitches.findVenue(details.venueId()).orElseThrow(() -> BusinessException.invalid("That venue could not be found."));
			game.playAt(venue.id(), venue.name(), venue.area(), null, null);
		}
		else {
			game.playAt(details.venueName(), details.venueArea());
		}
		games.save(game);

		if (details.pitchId() != null && game.getVenueId() != null) {
			var booked = pitches.bookForGame(game.getVenueId(), details.pitchId(), game.getStartsAt(), game.endsAt(), game.getId(), host);
			game.playAt(booked.venueId(), booked.venueName(), booked.venueArea(), booked.pitchId(), booked.pitchName());
			events.publishEvent(new GameEvents.PitchBooked(info(game), booked.venueId(), booked.ownerId(), booked.pitchName()));
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
		return Market.get(Market.DEFAULT).shareOf(game.getTotalCost(), game.getCapacity());
	}

	static GameEvents.GameInfo info(Game game) {
		return new GameEvents.GameInfo(game.getId(), game.getTitle(), game.getStartsAt(), game.getHostId(), game.getVenueName());
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
