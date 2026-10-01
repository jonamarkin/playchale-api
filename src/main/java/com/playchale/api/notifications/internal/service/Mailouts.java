package com.playchale.api.notifications.internal.service;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import com.playchale.api.integration.email.BulkEmailProperties;
import com.playchale.api.integration.email.Email;
import com.playchale.api.integration.email.EmailLayout;
import com.playchale.api.integration.email.EmailSender;
import com.playchale.api.shared.config.PlaychaleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Sending one message to many people, and keeping the record of it.
 *
 * <p>Every recipient is written down before anything is sent, and the row's primary key is the pair
 * (mailout, person): a run that is repeated, resumed or started twice cannot email the same person
 * twice for the same mailout, however it was interrupted. A claimed row moves to {@code sending}
 * before the network is touched and is never retried, because an email nobody can account for is
 * better missing than sent twice.
 *
 * <p>Nothing leaves at all unless this copy of the API was deliberately told it may
 * ({@code PLAYCHALE_EMAIL_BULK}), and never to a reserved address — so a mailout run against demo
 * data, or from a laptop pointed at a real database, does nothing.
 */
@Service
public class Mailouts {

	private static final Logger log = LoggerFactory.getLogger("email");

	/** How many are rendered and sent before the next claim. Keeps each transaction short. */
	private static final int BATCH = 50;

	private final JdbcClient jdbc;

	private final ObjectProvider<EmailSender> sender;

	private final BulkEmailProperties bulk;

	private final PlaychaleProperties properties;

	private final PushQueue queue;

	private final TransactionTemplate tx;

	private final ObjectMapper json;

	private final Clock clock;

	Mailouts(JdbcClient jdbc, ObjectProvider<EmailSender> sender, BulkEmailProperties bulk, PlaychaleProperties properties, PushQueue queue,
			TransactionTemplate tx, ObjectMapper json, Clock clock) {
		this.jdbc = jdbc;
		this.sender = sender;
		this.bulk = bulk;
		this.properties = properties;
		this.queue = queue;
		this.tx = tx;
		this.json = json;
		this.clock = clock;
	}

	/**
	 * What is being sent.
	 *
	 * @param kind      "announcement" or "digest"
	 * @param template  the card in resources/email, e.g. "digest"
	 * @param subject   the subject line, the same for everyone
	 * @param preheader the line inboxes show after the subject
	 */
	public record Letter(String kind, String template, String subject, String preheader) {
	}

	/** One person's copy: where it goes, what fills the template, and the link that stops it. */
	public record Copy(UUID userId, String address, Map<String, String> values, String unsubscribeUrl) {
	}

	/**
	 * Writes the mailout and everyone it is for, then sends it off the caller's thread.
	 *
	 * @param copies one per person; anyone whose address we can't use is written down as skipped
	 *               rather than dropped, so the record says who was left out and why
	 * @return the mailout's id
	 */
	public UUID send(Letter letter, UUID createdBy, List<Copy> copies) {
		var id = UUID.randomUUID();
		var now = clock.instant().atOffset(ZoneOffset.UTC);
		jdbc.sql("""
				INSERT INTO mailouts (id, kind, template, subject, preheader, payload, created_by, created_at)
				VALUES (:id, :kind, :template, :subject, :preheader, '{}'::jsonb, :by, :now)
				""")
			.param("id", id).param("kind", letter.kind()).param("template", letter.template()).param("subject", letter.subject())
			.param("preheader", letter.preheader()).param("by", createdBy).param("now", now)
			.update();
		for (var copy : copies) {
			var usable = BulkEmailProperties.deliverable(copy.address());
			jdbc.sql("""
					INSERT INTO mailout_recipients (mailout_id, user_id, email, status, reason)
					VALUES (:id, :user, :email, :status, :reason)
					ON CONFLICT (mailout_id, user_id) DO NOTHING
					""")
				.param("id", id).param("user", copy.userId()).param("email", usable ? copy.address() : "")
				.param("status", usable ? "pending" : "skipped").param("reason", usable ? null : "no usable address")
				.update();
		}
		var payload = copies.stream().filter(c -> BulkEmailProperties.deliverable(c.address())).toList();
		queue.submit(() -> run(id, letter, payload));
		return id;
	}

	/** Claims each person in turn and sends their copy. Safe to call again: claimed rows are never re-claimed. */
	void run(UUID id, Letter letter, List<Copy> copies) {
		var post = sender.getIfAvailable();
		if (post == null || !bulk.bulk()) {
			finish(id, "not sent: bulk email is off here");
			log.info("Mailout {} ({}) prepared for {} people but not sent: bulk email is off here", id, letter.kind(), copies.size());
			return;
		}
		var byUser = copies.stream().collect(java.util.stream.Collectors.toMap(Copy::userId, Function.identity(), (a, b) -> a));
		int sent = 0;
		for (var claimed : claim(id)) {
			var copy = byUser.get(claimed);
			try {
				post.send(letterFor(letter, copy));
				answer(id, claimed, "sent", null);
				sent++;
			}
			catch (RuntimeException e) {
				// One address refusing mustn't stop the rest; the row says so, and is not tried again.
				answer(id, claimed, "failed", e.getClass().getSimpleName());
				log.warn("Mailout {} could not reach one recipient", id, e);
			}
		}
		finish(id, null);
		log.info("Mailout {} ({}): {} sent of {}", id, letter.kind(), sent, copies.size());
	}

	private Email letterFor(Letter letter, Copy copy) {
		var html = EmailLayout.render(properties.webApp(), letter.template(), letter.subject(), letter.preheader(),
				footerFor(letter), copy.values(), copy.unsubscribeUrl());
		return new Email(copy.address(), letter.subject(), plainText(letter, copy), html);
	}

	/** Why they got it. Said plainly, because "you are receiving this because" is the first thing people look for. */
	private static String footerFor(Letter letter) {
		return "digest".equals(letter.kind()) ? "You get this because you asked PlayChale for a weekly round-up."
				: "You get this because you asked to hear from PlayChale.";
	}

	/** The plain-text copy, for mail apps that don't show HTML and for spam filters, which expect one. */
	private static String plainText(Letter letter, Copy copy) {
		var body = new StringBuilder(letter.subject()).append("\n\n");
		copy.values().forEach((key, value) -> body.append(value).append('\n'));
		if (copy.unsubscribeUrl() != null) {
			body.append("\nStop these emails: ").append(copy.unsubscribeUrl()).append('\n');
		}
		return body.toString();
	}

	/** The next few nobody else has taken, moved out of the queue in one statement. */
	private List<UUID> claim(UUID id) {
		return tx.execute(status -> jdbc.sql("""
				UPDATE mailout_recipients
				SET status = 'sending'
				WHERE (mailout_id, user_id) IN (
				    SELECT mailout_id, user_id FROM mailout_recipients
				    WHERE mailout_id = :id AND status = 'pending'
				    LIMIT :batch
				    FOR UPDATE SKIP LOCKED)
				RETURNING user_id
				""").param("id", id).param("batch", BATCH).query(UUID.class).list());
	}

	private void answer(UUID id, UUID user, String status, String reason) {
		tx.executeWithoutResult(s -> jdbc.sql("UPDATE mailout_recipients SET status = :status, reason = :reason, sent_at = :now WHERE mailout_id = :id AND user_id = :user")
			.param("status", status).param("reason", reason).param("now", clock.instant().atOffset(ZoneOffset.UTC))
			.param("id", id).param("user", user).update());
	}

	private void finish(UUID id, String note) {
		tx.executeWithoutResult(s -> {
			jdbc.sql("UPDATE mailouts SET finished_at = :now WHERE id = :id")
				.param("now", clock.instant().atOffset(ZoneOffset.UTC)).param("id", id).update();
			if (note != null) {
				jdbc.sql("UPDATE mailout_recipients SET status = 'skipped', reason = :note WHERE mailout_id = :id AND status = 'pending'")
					.param("note", note).param("id", id).update();
			}
		});
	}

}
