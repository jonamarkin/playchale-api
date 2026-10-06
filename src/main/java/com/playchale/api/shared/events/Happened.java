package com.playchale.api.shared.events;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes down what happened, for anyone asking later.
 *
 * <p>The rest of the schema holds the state of things now: a game is open, then full, then played,
 * and only the last of those survives. That is what the app needs and it cannot answer how long
 * games take to fill, what share of filled games get called off, or which venues turn bookings into
 * played games. Those answers can only come from a record made at the time — a row overwritten in
 * March is gone in June, whatever is bought in July.
 *
 * <p>So this is deliberately cheap to call and deliberately dumb: one append-only row, a type, a
 * subject, and whatever facts are worth keeping. It is not a queue, nothing reads it in the request,
 * and a failure to record must never fail the thing that happened — losing a statistic is a far
 * smaller harm than losing someone's game.
 *
 * <p>What goes in {@code details} is facts about the event, never personal detail the subject rows
 * do not already hold. Events about a person are removed with their account, like everything else.
 */
@Service
public class Happened {

	private static final Logger log = LoggerFactory.getLogger(Happened.class);

	private final JdbcClient jdbc;

	private final ObjectMapper json;

	private final Clock clock;

	Happened(JdbcClient jdbc, ObjectMapper json, Clock clock) {
		this.jdbc = jdbc;
		this.json = json;
		this.clock = clock;
	}

	/**
	 * Records an event.
	 *
	 * @param type        what happened, as "subject.verb": "game.created", "game.filled"
	 * @param subjectType what it happened to: "game", "venue", "competition"
	 * @param subjectId   which one
	 * @param actorId     who did it, or null when the app itself did
	 * @param details     facts worth keeping, small and not personal
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void record(String type, String subjectType, UUID subjectId, UUID actorId, Map<String, Object> details) {
		write(type, subjectType, subjectId, actorId, null, null, details);
	}

	/**
	 * The same, for something inside a workspace, so the corporate audit trail picks it up too.
	 *
	 * <p>In a transaction of its own, which is what actually makes "never fail what happened" true.
	 * Catching the exception is not enough: a failed statement poisons a Postgres transaction, so an
	 * event that would not write took the caller's work down with it however carefully it was caught.
	 * Its own transaction also means it can be written from a read-only one, which every screen that
	 * looks something up is.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void record(String type, String subjectType, UUID subjectId, UUID actorId, UUID organisationId, UUID competitionId,
			Map<String, Object> details) {
		write(type, subjectType, subjectId, actorId, organisationId, competitionId, details);
	}

	/**
	 * Both entry points call this rather than each other: a call from inside the class skips the
	 * proxy, so the overload that delegated was quietly running in its caller's transaction.
	 */
	private void write(String type, String subjectType, UUID subjectId, UUID actorId, UUID organisationId, UUID competitionId,
			Map<String, Object> details) {
		try {
			jdbc.sql("""
					INSERT INTO audit_events (id, organisation_id, competition_id, actor_id, event_type, subject_type, subject_id,
					                          details, occurred_at)
					VALUES (:id, :organisation, :competition, :actor, :type, :subjectType, :subject, CAST(:details AS jsonb), :at)
					""")
				.param("id", UUID.randomUUID())
				.param("organisation", organisationId)
				.param("competition", competitionId)
				.param("actor", actorId)
				.param("type", type)
				.param("subjectType", subjectType)
				.param("subject", subjectId)
				.param("details", json.writeValueAsString(details == null ? Map.of() : details))
				.param("at", clock.instant().atOffset(ZoneOffset.UTC))
				.update();
		}
		catch (Exception failed) {
			// Never fail what actually happened because we could not write it down.
			log.warn("Could not record {} on {} {}: {}", type, subjectType, subjectId, failed.toString());
		}
	}

}
