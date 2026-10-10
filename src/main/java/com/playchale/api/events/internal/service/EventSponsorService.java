package com.playchale.api.events.internal.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.playchale.api.events.internal.domain.GameSettings;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.shared.events.Happened;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An event's sponsors, for its public page, its board and its own page: a name, a logo, and one of
 * them the headline sponsor. The workspace's admins look after them.
 */
@Service
public class EventSponsorService {

	private static final int MAX_SPONSORS = 8;

	private final JdbcClient jdbc;

	private final Clock clock;

	private final EventAccess access;

	private final EventReader reader;

	private final Happened happened;

	EventSponsorService(JdbcClient jdbc, Clock clock, EventAccess access, EventReader reader, Happened happened) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.access = access;
		this.reader = reader;
		this.happened = happened;
	}

	/** A sponsor's name, and whether they're the headline sponsor (taking it from whoever was). */
	public record SponsorInput(String name, Boolean headline) {
	}

	public record Logo(byte[] bytes, String contentType) {
	}

	@Transactional
	public EventViews.Detail add(UUID eventId, SponsorInput input, UUID userId) {
		var event = access.requireAdmin(eventId, userId);
		var name = name(input);
		var count = jdbc.sql("SELECT count(*) FROM event_sponsors WHERE event_id = :event").param("event", eventId).query(Integer.class)
			.single();
		if (count >= MAX_SPONSORS) {
			throw BusinessException.invalid("An event can show up to %d sponsors.".formatted(MAX_SPONSORS));
		}
		var headline = Boolean.TRUE.equals(input.headline());
		if (headline) {
			clearHeadline(eventId);
		}
		var id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO event_sponsors (id, event_id, name, headline, position, created_at)
				VALUES (:id, :event, :name, :headline, :position, :now)
				""").param("id", id).param("event", eventId).param("name", name).param("headline", headline).param("position", count)
			.param("now", now()).update();
		happened.record("event.sponsor-added", "event", eventId, userId, event.organisationId(), null, Map.of("sponsor", id.toString()));
		return reader.detail(eventId, userId);
	}

	@Transactional
	public EventViews.Detail update(UUID eventId, UUID sponsorId, SponsorInput input, UUID userId) {
		access.requireAdmin(eventId, userId);
		var name = name(input);
		var headline = Boolean.TRUE.equals(input.headline());
		if (headline) {
			clearHeadline(eventId);
		}
		var changed = jdbc.sql("UPDATE event_sponsors SET name = :name, headline = :headline WHERE id = :id AND event_id = :event")
			.param("name", name).param("headline", headline).param("id", sponsorId).param("event", eventId).update();
		if (changed == 0) {
			throw BusinessException.notFound("That sponsor isn’t on this event any more.");
		}
		return reader.detail(eventId, userId);
	}

	@Transactional
	public EventViews.Detail remove(UUID eventId, UUID sponsorId, UUID userId) {
		var event = access.requireAdmin(eventId, userId);
		var removed = jdbc.sql("DELETE FROM event_sponsors WHERE id = :id AND event_id = :event").param("id", sponsorId)
			.param("event", eventId).update();
		if (removed == 0) {
			throw BusinessException.notFound("That sponsor isn’t on this event any more.");
		}
		happened.record("event.sponsor-removed", "event", eventId, userId, event.organisationId(), null, Map.of());
		return reader.detail(eventId, userId);
	}

	/** A sponsor's logo: PNG, JPEG or WebP, under 256 KB, checked by its first bytes as a workspace logo is. */
	@Transactional
	public EventViews.Detail logo(UUID eventId, UUID sponsorId, byte[] image, String contentType, UUID userId) {
		access.requireAdmin(eventId, userId);
		var type = contentType == null ? "" : contentType.split(";")[0].strip().toLowerCase(Locale.ROOT);
		var valid = image != null && image.length > 4
				&& ("image/png".equals(type) && image[0] == (byte) 0x89 && image[1] == 'P'
						|| "image/jpeg".equals(type) && image[0] == (byte) 0xff && image[1] == (byte) 0xd8
						|| "image/webp".equals(type) && image[0] == 'R' && image[1] == 'I');
		if (!valid || image.length > 256 * 1024) {
			throw BusinessException.invalid("Use a PNG, JPEG or WebP logo under 256 KB.");
		}
		var changed = jdbc.sql("""
				UPDATE event_sponsors SET logo = :logo, logo_content_type = :type, logo_version = logo_version + 1
				WHERE id = :id AND event_id = :event
				""").param("logo", image).param("type", type).param("id", sponsorId).param("event", eventId).update();
		if (changed == 0) {
			throw BusinessException.notFound("That sponsor isn’t on this event any more.");
		}
		return reader.detail(eventId, userId);
	}

	/** A sponsor's logo, for anyone: it's on the public page and the board. */
	public Logo logo(UUID sponsorId) {
		return jdbc.sql("SELECT logo, logo_content_type FROM event_sponsors WHERE id = :id AND logo IS NOT NULL").param("id", sponsorId)
			.query((rs, n) -> new Logo(rs.getBytes(1), rs.getString(2))).optional()
			.orElseThrow(() -> BusinessException.notFound("That logo doesn’t exist."));
	}

	private void clearHeadline(UUID eventId) {
		jdbc.sql("UPDATE event_sponsors SET headline = false WHERE event_id = :event AND headline").param("event", eventId).update();
	}

	private static String name(SponsorInput input) {
		var name = GameSettings.text(input == null ? null : input.name(), 60);
		if (name == null) {
			throw BusinessException.invalid("Say who the sponsor is.");
		}
		return name;
	}

	private OffsetDateTime now() {
		return clock.instant().atOffset(ZoneOffset.UTC);
	}

}
