package com.playchale.api.games.internal.service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.playchale.api.games.api.GameEvents;
import com.playchale.api.games.api.GameMessageResponse;
import com.playchale.api.games.internal.domain.Game;
import com.playchale.api.games.internal.domain.GameMessage;
import com.playchale.api.games.internal.repository.GameMessageRepository;
import com.playchale.api.games.internal.repository.GameRepository;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.users.api.UserDirectory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Talk about one game, between the people in it.
 *
 * <p>Membership is the whole of the access rule and the whole of the moderation model: you can read
 * and write only where you hold a spot, so there is no public surface to spam and the audience is
 * the people who will be standing on the pitch together. Anything out of order is taken down by its
 * author or by the host, both of whom are in the game and answerable to it. That is why this exists
 * and a wall of posts does not — a feed would need reporting, blocking and someone to answer them.
 */
@Service
public class GameTalk {

	private final GameRepository games;

	private final GameMessageRepository messages;

	private final UserDirectory users;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	GameTalk(GameRepository games, GameMessageRepository messages, UserDirectory users, ApplicationEventPublisher events, Clock clock) {
		this.games = games;
		this.messages = messages;
		this.users = users;
		this.events = events;
		this.clock = clock;
	}

	/** games.messages: what has been said about this game, oldest first. */
	@Transactional(readOnly = true)
	public List<GameMessageResponse> list(UUID gameId, UUID me) {
		inTheGame(gameId, me);
		return view(messages.findByGameIdOrderByCreatedAt(gameId), me);
	}

	/** games.say: add to it. */
	@Transactional
	public List<GameMessageResponse> say(UUID gameId, String body, UUID me) {
		var game = inTheGame(gameId, me);
		var saved = messages.save(new GameMessage(gameId, me, body, clock.instant()));
		// Everyone else in the game, so "running ten minutes late" actually reaches them.
		var others = game.getParticipants().stream().map(p -> p.getUserId()).filter(id -> id != null && !id.equals(me)).distinct().toList();
		if (!others.isEmpty()) {
			events.publishEvent(new GameEvents.MessagePosted(info(game), me, saved.getBody(), others));
		}
		return view(messages.findByGameIdOrderByCreatedAt(gameId), me);
	}

	/** games.removeMessage: its author, or the host. */
	@Transactional
	public List<GameMessageResponse> remove(UUID gameId, UUID messageId, UUID me) {
		var game = inTheGame(gameId, me);
		var message = messages.findById(messageId).filter(m -> m.getGameId().equals(gameId))
			.orElseThrow(() -> BusinessException.notFound("That message is already gone."));
		if (!message.canBeRemovedBy(me, game.getHostId())) {
			throw BusinessException.conflict("Only whoever said it, or the host, can take it down.");
		}
		messages.delete(message);
		return view(messages.findByGameIdOrderByCreatedAt(gameId), me);
	}

	/**
	 * The game, if this person is in it. A game you aren't in says nothing to you, and says so the
	 * same way whether or not it exists: who is in a private game is not a thing to leak by asking.
	 */
	private Game inTheGame(UUID gameId, UUID me) {
		var game = games.findById(gameId).orElseThrow(() -> BusinessException.notFound("That game could not be found."));
		if (!game.isHost(me) && game.spotOf(me).isEmpty()) {
			throw BusinessException.notFound("That game could not be found.");
		}
		return game;
	}

	/**
	 * Everyone is shown the way the rest of the app shows them: {@code as(viewer)} keeps a person's
	 * phone, email and payout number to themselves. Being in a game together is not a reason to hand
	 * out someone's number, and a public game is one tap to join.
	 */
	private List<GameMessageResponse> view(List<GameMessage> rows, UUID viewer) {
		var people = users.findAll(rows.stream().map(GameMessage::getUserId).distinct().toList());
		return rows.stream()
			.map(m -> new GameMessageResponse(m.getId(), people.get(m.getUserId()).as(viewer), m.getBody(), m.getCreatedAt()))
			.toList();
	}

	private GameEvents.GameInfo info(Game game) {
		return new GameEvents.GameInfo(game.getId(), game.getTitle(), game.getStartsAt(), game.getHostId(), game.getVenueName(),
				game.getCountry(), game.getTimezone());
	}

}
