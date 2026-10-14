package com.playchale.api.admin.internal.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import com.playchale.api.integration.email.Email;
import com.playchale.api.integration.email.EmailLayout;
import com.playchale.api.integration.email.EmailSender;
import com.playchale.api.shared.config.PlaychaleProperties;
import com.playchale.api.shared.scheduling.ClusterLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tells staff by email when something is waiting for them on the admin desk: a venue to check, or a
 * report. Nobody has to keep the desk open to notice.
 *
 * <p>Looked at every minute, sent at most every {@link #GAP}, and only for things that came in since
 * the last email: a burst of reports is one email, and nothing new is no email. Each round is a row
 * in desk_alerts, so the spacing holds across restarts and copies of the API.
 */
@Component
class DeskAlerts {

	static final Duration GAP = Duration.ofMinutes(10);

	private static final Logger log = LoggerFactory.getLogger(DeskAlerts.class);

	private final JdbcClient jdbc;

	private final ClusterLock lock;

	private final Clock clock;

	private final ObjectProvider<EmailSender> email;

	private final PlaychaleProperties playchale;

	private final AdminProperties admin;

	DeskAlerts(JdbcClient jdbc, ClusterLock lock, Clock clock, ObjectProvider<EmailSender> email, PlaychaleProperties playchale,
			AdminProperties admin) {
		this.jdbc = jdbc;
		this.lock = lock;
		this.clock = clock;
		this.email = email;
		this.playchale = playchale;
		this.admin = admin;
	}

	/** What a round found: new since the last email, and everything waiting now. */
	record Waiting(long newVenues, long newReports, long venues, long reports) {
	}

	@Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT1M")
	@Transactional
	public void send() {
		if (!lock.tryLock("desk-alerts")) {
			return;
		}
		var now = clock.instant();
		var last = jdbc.sql("SELECT max(sent_at) FROM desk_alerts").query(OffsetDateTime.class).optional().map(OffsetDateTime::toInstant).orElse(null);
		if (last != null && last.plus(GAP).isAfter(now)) {
			return;
		}
		var since = last == null ? Instant.EPOCH : last;
		var waiting = jdbc.sql("""
				SELECT (SELECT count(*) FROM venues WHERE review = 'pending' AND updated_at > :since) AS new_venues,
				       (SELECT count(*) FROM reports WHERE status = 'open' AND created_at > :since) AS new_reports,
				       (SELECT count(*) FROM venues WHERE review = 'pending') AS venues,
				       (SELECT count(DISTINCT (subject_type, subject_id)) FROM reports WHERE status = 'open') AS reports
				""").param("since", since.atOffset(ZoneOffset.UTC)).query(Waiting.class).single();
		if (waiting.newVenues() == 0 && waiting.newReports() == 0) {
			return;
		}
		var addresses = jdbc.sql("""
				SELECT coalesce(u.sign_in_email, u.email) FROM platform_staff s JOIN users u ON u.id = s.user_id
				WHERE u.deleted_at IS NULL AND coalesce(u.sign_in_email, u.email) IS NOT NULL
				""").query(String.class).list();
		var sender = email.getIfAvailable();
		int sent = 0;
		if (sender == null) {
			log.warn("Something is waiting on the admin desk, but no email is set up to tell staff");
		}
		else {
			var message = message(waiting);
			for (var to : addresses) {
				try {
					sender.send(new Email(to, message.subject(), message.text(), message.html()));
					sent++;
				}
				catch (RuntimeException failed) {
					log.warn("Couldn't tell {} what's waiting on the desk: {}", to, failed.toString());
				}
			}
		}
		// Written even when nobody could be told, so the next round waits its turn rather than trying every minute.
		jdbc.sql("INSERT INTO desk_alerts (sent_at, venues, reports, sent_to) VALUES (:now, :venues, :reports, :sent)")
			.param("now", now.atOffset(ZoneOffset.UTC)).param("venues", waiting.newVenues()).param("reports", waiting.newReports())
			.param("sent", sent).update();
	}

	private record Message(String subject, String text, String html) {
	}

	private Message message(Waiting w) {
		var fresh = join(count(w.newVenues(), "venue to check", "venues to check"), count(w.newReports(), "report", "reports"));
		var waiting = join(count(w.venues(), "venue", "venues"), count(w.reports(), "reported thing", "reported things"));
		var now = waiting.isEmpty() ? "nothing, someone has seen to it already" : waiting;
		var subject = "PlayChale: new on the desk, %s".formatted(fresh);
		var since = "New since the last email: %s.".formatted(fresh);
		var link = admin.url().replaceAll("/+$", "") + (w.newReports() > 0 ? "/reports" : "/venues");
		var text = "%s\nWaiting now: %s.\n\nOpen the admin desk: %s".formatted(since, now, link);
		var html = EmailLayout.render(playchale.webApp(), "desk-alert", subject, since, "You get this because you work on PlayChale’s admin desk.",
				Map.of("headline", "Something’s waiting for you", "since", since, "waiting", now, "link", link));
		return new Message(subject, text, html);
	}

	private static String count(long n, String one, String many) {
		return n == 0 ? "" : "%d %s".formatted(n, n == 1 ? one : many);
	}

	private static String join(String a, String b) {
		return a.isEmpty() ? b : b.isEmpty() ? a : a + " and " + b;
	}

}
