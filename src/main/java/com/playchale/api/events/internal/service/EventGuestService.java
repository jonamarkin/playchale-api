package com.playchale.api.events.internal.service;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.playchale.api.market.Market;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.shared.events.Happened;
import com.playchale.api.shared.security.RateLimiter;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Signing up to a games day through its link without an account: a name, a phone number, their
 * group and the games they want, just as someone signed in does (EventService.join). Their phone
 * keeps a token for the place, shown only now, with which they see their games, change them while
 * sign-ups are open, or leave. Whoever later signs in with that number gets the place
 * ({@link #claimHeldFor}); so does whoever signs in on that phone and hands the token over.
 *
 * <p>Only the organisers see the number. A church's Wi-Fi puts a whole hall behind one address, so a
 * connection can sign up thirty people an hour; one event takes five hundred a day.
 */
@Service
public class EventGuestService {

	static final int PER_CONNECTION_PER_HOUR = 30;

	static final int PER_EVENT_PER_DAY = 500;

	private final JdbcClient jdbc;

	private final Clock clock;

	private final EventAccess access;

	private final EventReader reader;

	private final EventService events;

	private final RateLimiter limiter;

	private final Happened happened;

	EventGuestService(JdbcClient jdbc, Clock clock, EventAccess access, EventReader reader, EventService events, RateLimiter limiter,
			Happened happened) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.access = access;
		this.reader = reader;
		this.events = events;
		this.limiter = limiter;
		this.happened = happened;
	}

	/** A place, as found by the token their phone keeps. */
	private record Place(UUID personId, UUID eventId, UUID userId) {
	}

	@Transactional
	public EventViews.GuestJoined join(String code, String name, String phone, UUID groupId, List<UUID> gameIds, String connection) {
		if (!limiter.tryAcquire("event-guest-join:" + connection, Duration.ofHours(1), PER_CONNECTION_PER_HOUR)) {
			throw BusinessException.conflict("Too many sign-ups from this connection. Try again in an hour, or sign in.");
		}
		var eventId = events.byCode(code);
		var event = access.event(eventId);
		if (!event.open() || !event.registrationOpen()) {
			throw BusinessException.conflict("%s isn’t taking sign-ups now. Ask the organisers to add you.".formatted(event.name()));
		}
		if (!limiter.tryAcquire("event-guest-join-event:" + eventId, Duration.ofDays(1), PER_EVENT_PER_DAY)) {
			throw BusinessException.conflict("This event has had a lot of sign-ups today. Ask the organisers to add you.");
		}
		var groups = events.groupIds(eventId);
		if (!groups.isEmpty() && groupId == null) {
			throw BusinessException.invalid("Pick your group.");
		}
		if (groupId != null && !groups.contains(groupId)) {
			throw BusinessException.invalid("That group isn’t in this event any more.");
		}
		var displayName = EventService.personName(name == null || name.isBlank() ? "" : name);
		var country = jdbc.sql("SELECT country FROM events WHERE id = :id").param("id", eventId).query(String.class).single();
		var e164 = Market.get(country).normalisePhone(phone == null ? "" : phone)
			.orElseThrow(() -> BusinessException.invalid(phone == null || phone.isBlank() ? "Add your phone number, so the organisers can reach you."
					: "That number doesn’t look right. Check it and try again."));
		var token = EventService.code(24);
		var personId = UUID.randomUUID();
		var now = clock.instant().atOffset(ZoneOffset.UTC);
		try {
			jdbc.sql("""
					INSERT INTO event_people (id, event_id, display_name, group_id, source, guest_phone, guest_hash, created_at, updated_at)
					VALUES (:id, :event, :name, :group, 'guest', :phone, :hash, :now, :now)
					""").param("id", personId).param("event", eventId).param("name", displayName).param("group", groupId)
				.param("phone", e164).param("hash", EventService.sha256(token)).param("now", now).update();
		}
		catch (DuplicateKeyException twice) {
			throw BusinessException.conflict("That number has already signed up. Open the link on the phone you used, or ask the organisers.");
		}
		events.setGames(eventId, personId, gameIds == null ? List.of() : gameIds);
		happened.record("event.guest-joined", "event", eventId, null, event.organisationId(), null, Map.of());
		return new EventViews.GuestJoined(token, personId);
	}

	/** Their place, on the phone that signed up. */
	@Transactional(readOnly = true)
	public EventViews.GuestPlace place(String token) {
		var place = find(token);
		return reader.guestPlace(place.eventId(), place.personId());
	}

	/** Changes their group and their games, while the event takes sign-ups. Games left out are games they're leaving. */
	@Transactional
	public EventViews.GuestPlace change(String token, UUID groupId, List<UUID> gameIds) {
		var place = find(token);
		var event = access.event(place.eventId());
		if (!event.open() || !event.registrationOpen()) {
			throw BusinessException.conflict("%s isn’t taking sign-ups now. Ask the organisers to change your games.".formatted(event.name()));
		}
		var groups = events.groupIds(place.eventId());
		if (groupId != null && !groups.contains(groupId)) {
			throw BusinessException.invalid("That group isn’t in this event any more.");
		}
		if (!groups.isEmpty() && groupId == null) {
			throw BusinessException.invalid("Pick your group.");
		}
		var name = jdbc.sql("SELECT display_name FROM event_people WHERE id = :id").param("id", place.personId()).query(String.class).single();
		jdbc.sql("UPDATE event_people SET group_id = :group, updated_at = :now WHERE id = :id").param("group", groupId)
			.param("now", clock.instant().atOffset(ZoneOffset.UTC)).param("id", place.personId()).update();
		events.moveSingles(place.personId(), name, groupId);
		events.setGames(place.eventId(), place.personId(), gameIds == null ? List.of() : gameIds);
		return reader.guestPlace(place.eventId(), place.personId());
	}

	/** Leaves the event, while they're not in a draw: as an organiser taking someone out would. */
	@Transactional
	public void leave(String token) {
		var place = find(token);
		var drawn = jdbc.sql("""
				SELECT g.name FROM event_entry_people ep JOIN event_games g ON g.id = ep.game_id
				WHERE ep.person_id = :person AND g.status <> 'open' AND g.entry_kind = 'single' LIMIT 1
				""").param("person", place.personId()).query(String.class).optional();
		if (drawn.isPresent()) {
			throw BusinessException.conflict("You’re in the draw for %s, so ask the organisers to take you out.".formatted(drawn.get()));
		}
		jdbc.sql("""
				DELETE FROM event_entries e USING event_entry_people ep, event_games g
				WHERE ep.entry_id = e.id AND g.id = e.game_id AND ep.person_id = :person AND g.entry_kind = 'single'
				""").param("person", place.personId()).update();
		jdbc.sql("DELETE FROM event_people WHERE id = :id").param("id", place.personId()).update();
	}

	/**
	 * Hands the place to the account signed in on that phone. Refused if they're in the event already,
	 * under another name: the organisers can sort that out.
	 */
	@Transactional
	public EventViews.Detail claim(String token, UUID userId) {
		var place = find(token);
		var already = jdbc.sql("SELECT display_name FROM event_people WHERE event_id = :event AND user_id = :user")
			.param("event", place.eventId()).param("user", userId).query(String.class).optional();
		if (already.isPresent()) {
			throw BusinessException.conflict("You’re already in this event as %s. Ask the organisers to put the two together.".formatted(already.get()));
		}
		attach(place.personId(), userId);
		return reader.detail(place.eventId(), userId);
	}

	/**
	 * Every guest place signed up with {@code phone} becomes the account's, unless that account is in
	 * the event already: someone just proved the number is theirs. Its own transaction, so a place
	 * that can't move never stops anyone signing in. Returns how many moved.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int claimHeldFor(UUID userId, String phone) {
		if (phone == null || phone.isBlank()) {
			return 0;
		}
		var places = jdbc.sql("""
				SELECT p.id FROM event_people p
				WHERE p.guest_phone = :phone AND p.user_id IS NULL
				  AND NOT EXISTS (SELECT 1 FROM event_people mine WHERE mine.event_id = p.event_id AND mine.user_id = :user)
				""").param("phone", phone).param("user", userId).query(UUID.class).list();
		places.forEach(personId -> attach(personId, userId));
		return places.size();
	}

	private void attach(UUID personId, UUID userId) {
		jdbc.sql("""
				UPDATE event_people SET user_id = :user, guest_phone = NULL, guest_hash = NULL, updated_at = :now WHERE id = :id
				""").param("user", userId).param("now", clock.instant().atOffset(ZoneOffset.UTC)).param("id", personId).update();
	}

	private Place find(String token) {
		var clean = token == null ? "" : token.strip();
		return jdbc.sql("SELECT id, event_id, user_id FROM event_people WHERE guest_hash = :hash")
			.param("hash", EventService.sha256(clean))
			.query((rs, n) -> new Place((UUID) rs.getObject(1), (UUID) rs.getObject(2), (UUID) rs.getObject(3))).optional()
			.orElseThrow(() -> BusinessException.notFound("That place isn’t there any more. The organisers may have taken it off."));
	}

}
