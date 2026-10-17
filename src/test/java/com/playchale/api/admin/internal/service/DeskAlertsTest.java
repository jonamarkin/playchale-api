package com.playchale.api.admin.internal.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.playchale.api.TestcontainersConfiguration;
import com.playchale.api.integration.email.Email;
import com.playchale.api.integration.email.EmailSender;
import com.playchale.api.reports.internal.service.ReportService;
import com.playchale.api.shared.config.PlaychaleProperties;
import com.playchale.api.shared.scheduling.ClusterLock;
import com.playchale.api.shared.TestClock;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.venues.internal.domain.DayHours;
import com.playchale.api.venues.internal.domain.PitchDetails;
import com.playchale.api.venues.internal.domain.VenueDetails;
import com.playchale.api.venues.internal.service.VenueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Staff hear by email when something is waiting on the desk: once for a burst, never more often than
 * every ten minutes, and not at all when nothing new came in.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, DeskAlertsTest.Setup.class })
class DeskAlertsTest {

	@TestConfiguration
	static class Setup {

		@Bean
		@Primary
		TestClock testClock() {
			return new TestClock();
		}

		@Bean
		@Primary
		Outbox outbox() {
			return new Outbox();
		}

	}

	/** The emails that would have gone out. */
	static class Outbox implements EmailSender {

		final List<Email> sent = new CopyOnWriteArrayList<>();

		@Override
		public void send(Email email) {
			sent.add(email);
		}

	}

	private static final Instant NOW = Instant.parse("2026-09-25T08:00:00Z");

	@Autowired
	DeskAlerts alerts;

	@Autowired
	Outbox outbox;

	@Autowired
	TestClock clock;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	UserDirectory users;

	@Autowired
	VenueService venues;

	@Autowired
	ReportService reports;

	@Autowired
	ClusterLock lock;

	@Autowired
	PlaychaleProperties playchale;

	@Autowired
	ObjectProvider<EmailSender> senders;

	UUID owner;

	UUID player;

	@BeforeEach
	void setUp() {
		jdbc.sql("TRUNCATE users, sessions, platform_staff, venues, pitches, reports, desk_alerts, rate_limits, notifications CASCADE").update();
		outbox.sent.clear();
		clock.set(NOW);
		owner = users.registerOrFind("+233244555401", "GH").id();
		player = users.registerOrFind("+233244555402", "GH").id();
		jdbc.sql("UPDATE users SET sign_in_email = 'ama@playchale.example' WHERE id = :id").param("id", owner).update();
		jdbc.sql("INSERT INTO platform_staff (user_id, role) VALUES (:id, 'owner')").param("id", owner).update();
	}

	@Test
	void aBurstIsOneEmailAndNothingNewIsNone() {
		alerts.send();
		assertThat(outbox.sent).as("nothing waiting").isEmpty();

		listVenue("Dansoman Astro");
		listVenue("Kaneshie Five-a-side");
		reports.send(player, "user", owner, "abuse", null);
		alerts.send();
		assertThat(outbox.sent).singleElement().satisfies(email -> {
			assertThat(email.to()).isEqualTo("ama@playchale.example");
			assertThat(email.subject()).isEqualTo("PlayChale: new on the desk, 2 venues to check and 1 report");
			// To the desk's Reports (a report came in), wherever the desk is (playchale.admin.url).
			assertThat(email.text()).containsPattern("Open the admin desk: https?://\\S+/reports");
		});

		// Within ten minutes: another venue waits for the next round.
		clock.set(NOW.plus(Duration.ofMinutes(5)));
		listVenue("Tema Turf");
		alerts.send();
		assertThat(outbox.sent).hasSize(1);

		clock.set(NOW.plus(Duration.ofMinutes(11)));
		alerts.send();
		assertThat(outbox.sent).hasSize(2);
		assertThat(outbox.sent.getLast().subject()).isEqualTo("PlayChale: new on the desk, 1 venue to check");
		assertThat(outbox.sent.getLast().text()).contains("Waiting now: 3 venues and 1 reported thing.");

		// Still waiting, but nothing new: no email.
		clock.set(NOW.plus(Duration.ofMinutes(30)));
		alerts.send();
		assertThat(outbox.sent).hasSize(2);
	}

	@Test
	void switchedOffNoStaffEmailGoesWhateverIsWaiting() {
		listVenue("Dansoman Astro");
		reports.send(player, "user", owner, "abuse", null);
		var off = new DeskAlerts(jdbc, lock, clock, senders, playchale, new AdminProperties("https://admin.playchale.com", false));
		off.send();
		assertThat(outbox.sent).isEmpty();
		assertThat(jdbc.sql("SELECT count(*) FROM desk_alerts").query(Integer.class).single()).as("nothing counted as sent").isZero();
	}

	private void listVenue(String name) {
		venues.create(player, new VenueDetails(name, "Accra", null, null, null, null,
				List.of(new PitchDetails(null, "Pitch A", "football", "5-a-side", "turf", 25_000)), Collections.nCopies(7, new DayHours("06:00", "23:00")),
				List.of()));
	}

}
