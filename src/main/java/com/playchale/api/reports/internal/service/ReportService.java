package com.playchale.api.reports.internal.service;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.playchale.api.reports.api.ReportDecisions;
import com.playchale.api.reports.api.ReportsClosed;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.shared.security.RateLimiter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A player reporting a game, a message, a person or a venue, and staff closing those reports.
 *
 * <p>Whether the thing exists is asked of its table directly, as the admin desk asks: a module method
 * on each of four modules for one "is it there?" isn't worth it, and a report about nothing would
 * only clutter the desk.
 */
@Service
public class ReportService implements ReportDecisions {

	/** Reports a person can send: enough for anyone honest, too few to bury the desk. */
	static final int PER_HOUR = 10;

	static final int PER_DAY = 30;

	static final Set<String> REASONS = Set.of("spam", "abuse", "unsafe", "wrong", "other");

	/** Where each kind of thing is, and how to tell it's still there. */
	private static final Map<String, String> EXISTS = Map.of(
			"game", "SELECT count(*) > 0 FROM games WHERE id = :id",
			"message", "SELECT count(*) > 0 FROM game_messages WHERE id = :id",
			"user", "SELECT count(*) > 0 FROM users WHERE id = :id AND deleted_at IS NULL",
			"venue", "SELECT count(*) > 0 FROM venues WHERE id = :id");

	private final JdbcClient jdbc;

	private final RateLimiter limiter;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	ReportService(JdbcClient jdbc, RateLimiter limiter, ApplicationEventPublisher events, Clock clock) {
		this.jdbc = jdbc;
		this.limiter = limiter;
		this.events = events;
		this.clock = clock;
	}

	/**
	 * reports.send: {@code reporter} tells PlayChale about something. The same person reporting the
	 * same thing again, while it's open, is the same report (its reason and note brought up to date).
	 */
	@Transactional
	public void send(UUID reporter, String subjectType, UUID subjectId, String reason, String note) {
		var type = subjectType == null ? "" : subjectType.strip();
		var exists = EXISTS.get(type);
		if (exists == null || subjectId == null) {
			throw BusinessException.invalid("Say what you’re reporting.");
		}
		if (!REASONS.contains(reason)) {
			throw BusinessException.invalid("Pick what’s wrong.");
		}
		if ("user".equals(type) && subjectId.equals(reporter)) {
			throw BusinessException.invalid("That’s you.");
		}
		if (!jdbc.sql(exists).param("id", subjectId).query(Boolean.class).single()) {
			throw BusinessException.notFound("That isn’t there any more.");
		}
		var said = note == null ? null : note.strip();
		if (said != null && said.length() > 500) {
			throw BusinessException.invalid("Keep it under 500 characters.");
		}
		if (!limiter.tryAcquire("report:" + reporter, Duration.ofHours(1), PER_HOUR)
				|| !limiter.tryAcquire("report-daily:" + reporter, Duration.ofDays(1), PER_DAY)) {
			throw BusinessException.conflict("That’s a lot of reports. Try again later, or write to PlayChale’s support.");
		}
		jdbc.sql("""
				INSERT INTO reports (id, reporter_id, subject_type, subject_id, reason, note, created_at)
				VALUES (:id, :reporter, :type, :subject, :reason, :note, :now)
				ON CONFLICT (reporter_id, subject_type, subject_id) WHERE status = 'open'
				DO UPDATE SET reason = EXCLUDED.reason, note = coalesce(EXCLUDED.note, reports.note)
				""").param("id", UUID.randomUUID()).param("reporter", reporter).param("type", type).param("subject", subjectId)
			.param("reason", reason).param("note", said == null || said.isEmpty() ? null : said)
			.param("now", clock.instant().atOffset(ZoneOffset.UTC)).update();
	}

	@Override
	@Transactional
	public int actioned(String subjectType, UUID subjectId, String note, UUID staffId) {
		return close(subjectType, subjectId, "actioned", note, staffId);
	}

	@Override
	@Transactional
	public int dismissed(String subjectType, UUID subjectId, String note, UUID staffId) {
		return close(subjectType, subjectId, "dismissed", note, staffId);
	}

	private int close(String subjectType, UUID subjectId, String outcome, String note, UUID staffId) {
		var said = note == null || note.isBlank() ? null : note.strip().substring(0, Math.min(280, note.strip().length()));
		var reporters = jdbc.sql("""
				UPDATE reports SET status = :outcome, outcome_note = :note, decided_by = :staff, decided_at = :now
				WHERE subject_type = :type AND subject_id = :subject AND status = 'open'
				RETURNING reporter_id
				""").param("outcome", outcome).param("note", said).param("staff", staffId).param("now", clock.instant().atOffset(ZoneOffset.UTC))
			.param("type", subjectType).param("subject", subjectId).query(UUID.class).list();
		if (!reporters.isEmpty()) {
			events.publishEvent(new ReportsClosed(subjectType, subjectId, reporters.stream().distinct().toList()));
		}
		return reporters.size();
	}

}
