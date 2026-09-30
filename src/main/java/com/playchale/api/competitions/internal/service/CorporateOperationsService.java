package com.playchale.api.competitions.internal.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;
import com.playchale.api.competitions.api.CorporateEvents;
import com.playchale.api.competitions.internal.domain.RoundRobin;
import com.playchale.api.games.api.Fixtures;
import com.playchale.api.games.api.OfficialResults;
import com.playchale.api.organisations.api.OrganisationAccess;
import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.venues.api.PitchBookings;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Corporate league operations layered on the existing competition and game aggregates. */
@Service
public class CorporateOperationsService {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcClient jdbc;
	private final Clock clock;
	private final Fixtures fixtures;
	private final OfficialResults results;
	private final PitchBookings bookings;
	private final OrganisationAccess organisations;
	private final ApplicationEventPublisher events;
	private final ObjectMapper json;

	public CorporateOperationsService(JdbcClient jdbc, Clock clock, Fixtures fixtures, OfficialResults results,
			PitchBookings bookings, OrganisationAccess organisations, ApplicationEventPublisher events, ObjectMapper json) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.fixtures = fixtures;
		this.results = results;
		this.bookings = bookings;
		this.organisations = organisations;
		this.events = events;
		this.json = json;
	}

	@Transactional(readOnly = true)
	public CorporateViews.Dashboard dashboard(UUID competitionId, UUID userId) {
		var competition = requireManager(competitionId, userId);
		var teams = number("SELECT count(*) FROM competition_entries WHERE competition_id = :id", competitionId);
		var confirmed = number("SELECT count(*) FROM competition_entries WHERE competition_id = :id AND status = 'entered'", competitionId);
		var submitted = number("SELECT count(DISTINCT team_id) FROM roster_members WHERE competition_id = :id AND eligibility_state = 'submitted'", competitionId);
		var approved = number("SELECT count(DISTINCT team_id) FROM roster_members WHERE competition_id = :id AND eligibility_state = 'approved'", competitionId);
		var rosterTotal = number("SELECT count(*) FROM roster_members WHERE competition_id = :id", competitionId);
		var claimed = number("SELECT count(*) FROM roster_members WHERE competition_id = :id AND user_id IS NOT NULL", competitionId);
		var fixtureTotal = number("SELECT count(*) FROM games WHERE competition_id = :id", competitionId);
		var played = number("SELECT count(*) FROM games WHERE competition_id = :id AND status = 'completed'", competitionId);
		var missing = number("SELECT count(*) FROM games g LEFT JOIN fixture_officials f ON f.game_id = g.id WHERE g.competition_id = :id AND f.game_id IS NULL", competitionId);
		var money = jdbc.sql("""
				SELECT coalesce(sum(amount_due),0), coalesce(sum(amount_due) FILTER (WHERE status = 'paid'),0)
				FROM competition_entry_finance WHERE competition_id = :id
				""").param("id", competitionId).query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}).single();
		var match = jdbc.sql("""
				SELECT (SELECT count(*) FROM match_sheet_players p JOIN games g ON g.id=p.game_id WHERE g.competition_id=:id AND p.checked_in),
				       (SELECT coalesce(sum(p.goals),0) FROM match_sheet_players p JOIN games g ON g.id=p.game_id WHERE g.competition_id=:id),
				       (SELECT count(*) FROM match_sheet_cards c JOIN games g ON g.id=c.game_id WHERE g.competition_id=:id AND c.colour='yellow'),
				       (SELECT count(*) FROM match_sheet_cards c JOIN games g ON g.id=c.game_id WHERE g.competition_id=:id AND c.colour='red')
				""").param("id", competitionId).query((rs, n) -> new int[] {rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)}).single();
		var comms = jdbc.sql("""
				SELECT count(DISTINCT a.id), count(r.acknowledged_at) FROM competition_announcements a
				LEFT JOIN announcement_recipients r ON r.announcement_id=a.id WHERE a.competition_id=:id
				""").param("id", competitionId).query((rs, n) -> new int[] {rs.getInt(1), rs.getInt(2)}).single();
		return new CorporateViews.Dashboard(competitionId, competition.organisationId(), competition.name(), competition.scheduleStatus(),
			new CorporateViews.Counts(teams, teams - confirmed, confirmed), new CorporateViews.Counts(rosterTotal, submitted, claimed),
			new CorporateViews.Counts(fixtureTotal, Math.max(0, fixtureTotal - played), played), missing, money[0], money[1],
			match[0], match[1], match[2], match[3], comms[0], comms[1]);
	}

	@Transactional(readOnly = true)
	public List<CorporateViews.RosterMember> roster(UUID competitionId, UUID teamId, UUID userId) {
		requireTeamManager(competitionId, teamId, userId);
		return jdbc.sql("""
				SELECT id, team_id, display_name, employee_reference, user_id, eligibility_state, attested_by, attested_at,
				       reviewed_by, reviewed_at, review_note, created_at
				FROM roster_members WHERE competition_id=:competition AND team_id=:team ORDER BY display_name
				""").param("competition", competitionId).param("team", teamId).query((rs, n) -> roster(rs, null)).list();
	}

	@Transactional
	public CorporateViews.RosterMember addRosterMember(UUID competitionId, UUID teamId, String name,
			String employeeReference, UUID userId, UUID actorId) {
		requireTeamManager(competitionId, teamId, actorId);
		var cleanName = text(name, 80, "Give the player a name.");
		var cleanReference = optional(employeeReference, 80);
		var id = UUID.randomUUID();
		var now = clock.instant();
		var token = token();
		try {
			jdbc.sql("""
					INSERT INTO roster_members (id, competition_id, team_id, display_name, employee_reference, user_id,
					 eligibility_state, claim_token_hash, created_by, created_at, updated_at)
					VALUES (:id,:competition,:team,:name,:reference,:user,'draft',:hash,:actor,:now,:now)
					""").param("id", id).param("competition", competitionId).param("team", teamId).param("name", cleanName)
				.param("reference", cleanReference).param("user", userId).param("hash", hash(token)).param("actor", actorId).param("now", db(now)).update();
		}
		catch (DataIntegrityViolationException e) {
			throw BusinessException.conflict("That player is already on a roster in this competition.");
		}
		audit(competitionId, actorId, "roster.member-added", "roster-member", id, Map.of("teamId", teamId));
		return getRoster(id, "/rosters/claim?token=" + token);
	}

	@Transactional
	public List<CorporateViews.RosterMember> importRoster(UUID competitionId, UUID teamId, List<RosterRow> rows, UUID actorId) {
		if (rows == null || rows.isEmpty() || rows.size() > 200) {
			throw BusinessException.invalid("Import between 1 and 200 roster rows.");
		}
		var names = new LinkedHashSet<String>();
		for (var row : rows) {
			var clean = text(row.displayName(), 80, "Every roster row needs a player name.");
			if (!names.add(clean.toLowerCase())) {
				throw BusinessException.invalid("The import contains the same player more than once: " + clean + ".");
			}
		}
		var added = new ArrayList<CorporateViews.RosterMember>();
		for (var row : rows) {
			added.add(addRosterMember(competitionId, teamId, row.displayName(), row.employeeReference(), null, actorId));
		}
		return added;
	}

	@Transactional
	public List<CorporateViews.RosterMember> submitRoster(UUID competitionId, UUID teamId, boolean attest, UUID actorId) {
		requireTeamManager(competitionId, teamId, actorId);
		if (!attest) {
			throw BusinessException.invalid("Confirm that the roster contains eligible employees before submitting it.");
		}
		var now = clock.instant();
		var changed = jdbc.sql("""
				UPDATE roster_members SET eligibility_state='submitted', attested_by=:actor, attested_at=:now, updated_at=:now
				WHERE competition_id=:competition AND team_id=:team AND eligibility_state='draft'
				""").param("actor", actorId).param("now", db(now)).param("competition", competitionId).param("team", teamId).update();
		if (changed == 0) {
			throw BusinessException.conflict("Add draft players before submitting this roster.");
		}
		audit(competitionId, actorId, "roster.submitted", "team", teamId, Map.of("players", changed));
		return roster(competitionId, teamId, actorId);
	}

	@Transactional
	public List<CorporateViews.RosterMember> reviewRoster(UUID competitionId, UUID teamId, List<UUID> memberIds,
			String decision, String note, UUID actorId) {
		requireManager(competitionId, actorId);
		if (!Set.of("approved", "rejected").contains(decision) || memberIds == null || memberIds.isEmpty()) {
			throw BusinessException.invalid("Choose submitted players and approve or reject them.");
		}
		var now = clock.instant();
		var changed = 0;
		for (var memberId : memberIds) {
			changed += jdbc.sql("""
					UPDATE roster_members SET eligibility_state=:decision, reviewed_by=:actor, reviewed_at=:now,
					 review_note=:note, updated_at=:now
					WHERE id=:member AND competition_id=:competition AND team_id=:team AND eligibility_state='submitted'
					""").param("decision", decision).param("actor", actorId).param("now", db(now)).param("note", optional(note, 500))
				.param("member", memberId).param("competition", competitionId).param("team", teamId).update();
		}
		if (changed == 0) {
			throw BusinessException.conflict("Those players are no longer awaiting review.");
		}
		syncClaimedSquad(competitionId, teamId);
		audit(competitionId, actorId, "eligibility." + decision, "team", teamId, Map.of("players", changed));
		return roster(competitionId, teamId, actorId);
	}

	@Transactional
	public CorporateViews.RosterMember claim(String claimToken, UUID userId) {
		var member = jdbc.sql("""
				SELECT id, competition_id, team_id FROM roster_members
				WHERE claim_token_hash=:hash AND user_id IS NULL AND eligibility_state <> 'rejected' FOR UPDATE
				""").param("hash", hash(text(claimToken, 200, "That claim link isn’t valid.")))
			.query((rs, n) -> new ClaimRow((UUID) rs.getObject("id"), (UUID) rs.getObject("competition_id"), (UUID) rs.getObject("team_id")))
			.optional().orElseThrow(() -> BusinessException.conflict("That roster place was already claimed or is no longer eligible."));
		try {
			jdbc.sql("UPDATE roster_members SET user_id=:user, claim_token_hash=NULL, updated_at=:now WHERE id=:id")
				.param("user", userId).param("now", db(clock.instant())).param("id", member.id()).update();
		}
		catch (DataIntegrityViolationException e) {
			throw BusinessException.conflict("You already have a roster place in this competition.");
		}
		syncClaimedSquad(member.competitionId(), member.teamId());
		audit(member.competitionId(), userId, "roster.claimed", "roster-member", member.id(), Map.of());
		return getRoster(member.id(), null);
	}

	public record RosterRow(String displayName, String employeeReference) {
	}

	private CorporateViews.RosterMember getRoster(UUID id, String claimUrl) {
		return jdbc.sql("""
				SELECT id, team_id, display_name, employee_reference, user_id, eligibility_state, attested_by, attested_at,
				       reviewed_by, reviewed_at, review_note, created_at FROM roster_members WHERE id=:id
				""").param("id", id).query((rs, n) -> roster(rs, claimUrl)).single();
	}

	private CorporateViews.RosterMember roster(ResultSet rs, String claimUrl) throws SQLException {
		return new CorporateViews.RosterMember((UUID) rs.getObject("id"), (UUID) rs.getObject("team_id"), rs.getString("display_name"),
			rs.getString("employee_reference"), (UUID) rs.getObject("user_id"), rs.getString("eligibility_state"),
			(UUID) rs.getObject("attested_by"), instant(rs, "attested_at"), (UUID) rs.getObject("reviewed_by"), instant(rs, "reviewed_at"),
			rs.getString("review_note"), instant(rs, "created_at"), claimUrl);
	}

	private void syncClaimedSquad(UUID competitionId, UUID teamId) {
		var squad = jdbc.sql("""
				SELECT user_id FROM roster_members WHERE competition_id=:competition AND team_id=:team
				AND eligibility_state='approved' AND user_id IS NOT NULL
				""").param("competition", competitionId).param("team", teamId).query(UUID.class).list();
		jdbc.sql("DELETE FROM entry_players WHERE competition_id=:competition AND team_id=:team")
			.param("competition", competitionId).param("team", teamId).update();
		for (var user : squad) {
			jdbc.sql("INSERT INTO entry_players (competition_id,team_id,user_id,added_at) VALUES (:competition,:team,:user,:now)")
				.param("competition", competitionId).param("team", teamId).param("user", user).param("now", db(clock.instant())).update();
		}
		fixtures.of(competitionId).stream().filter(f -> teamId.equals(f.homeTeamId()) || teamId.equals(f.awayTeamId()))
			.forEach(f -> fixtures.syncSquad(f.gameId(), claimedSquad(competitionId, f.homeTeamId(), f.awayTeamId())));
	}

	private List<UUID> claimedSquad(UUID competitionId, UUID home, UUID away) {
		return jdbc.sql("""
				SELECT user_id FROM roster_members WHERE competition_id=:competition AND team_id IN (:home,:away)
				AND eligibility_state='approved' AND user_id IS NOT NULL
				""").param("competition", competitionId).param("home", home).param("away", away).query(UUID.class).list();
	}

	private int number(String sql, UUID competitionId) {
		return jdbc.sql(sql).param("id", competitionId).query(Integer.class).single();
	}

	private record ClaimRow(UUID id, UUID competitionId, UUID teamId) {
	}


	@Transactional
	public CorporateViews.Location addLocation(UUID competitionId, String name, String area, String mapUrl,
			UUID venueId, UUID pitchId, UUID actorId) {
		requireManager(competitionId, actorId);
		if (pitchId != null && venueId == null) {
			throw BusinessException.invalid("Pick the venue that owns this pitch.");
		}
		var id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO competition_locations (id,competition_id,name,area,map_url,venue_id,pitch_id,created_at)
				VALUES (:id,:competition,:name,:area,:map,:venue,:pitch,:now)
				""").param("id", id).param("competition", competitionId).param("name", text(name, 120, "Give the location a name."))
			.param("area", optional(area, 120)).param("map", optional(mapUrl, 500)).param("venue", venueId).param("pitch", pitchId)
			.param("now", db(clock.instant())).update();
		audit(competitionId, actorId, "location.created", "location", id, Map.of());
		return new CorporateViews.Location(id, name.strip(), optional(area, 120), optional(mapUrl, 500), venueId, pitchId);
	}

	@Transactional(readOnly = true)
	public List<CorporateViews.Location> locations(UUID competitionId, UUID actorId) {
		requireManager(competitionId, actorId);
		return jdbc.sql("SELECT id,name,area,map_url,venue_id,pitch_id FROM competition_locations WHERE competition_id=:id ORDER BY name")
			.param("id", competitionId).query((rs,n) -> new CorporateViews.Location((UUID) rs.getObject("id"), rs.getString("name"),
				rs.getString("area"), rs.getString("map_url"), (UUID) rs.getObject("venue_id"), (UUID) rs.getObject("pitch_id"))).list();
	}

	@Transactional
	public List<CorporateViews.Fixture> generateSchedule(UUID competitionId, UUID actorId) {
		var competition = requireManager(competitionId, actorId);
		var teams = jdbc.sql("""
				SELECT e.team_id,t.name FROM competition_entries e JOIN teams t ON t.id=e.team_id
				WHERE e.competition_id=:id AND e.status='entered' ORDER BY e.entered_at,e.team_id
				""").param("id", competitionId).query((rs,n) -> new TeamRow((UUID) rs.getObject(1), rs.getString(2))).list();
		if (teams.size() < 3) {
			throw BusinessException.conflict("Add at least three confirmed teams before generating the schedule.");
		}
		fixtures.discard(competitionId);
		var location = defaultLocation(competitionId, competition);
		var specs = new ArrayList<Fixtures.FixtureSpec>();
		var rounds = RoundRobin.rounds(teams.stream().map(TeamRow::id).toList());
		var names = new HashMap<UUID,String>();
		teams.forEach(t -> names.put(t.id(), t.name()));
		for (int r=0; r<rounds.size(); r++) {
			var roundStart = competition.startsAt().plus(r * 7L, ChronoUnit.DAYS);
			for (int slot=0; slot<rounds.get(r).size(); slot++) {
				var pair = rounds.get(r).get(slot);
				var starts = roundStart.plus((long) slot * competition.durationMinutes(), ChronoUnit.MINUTES);
				specs.add(new Fixtures.FixtureSpec(competitionId, r+1, pair.home(), pair.away(),
					"%s vs %s".formatted(names.get(pair.home()), names.get(pair.away())), competition.sport(), competition.format(),
					starts, competition.durationMinutes(), location.venueId() == null ? "unlisted" : "listed", location.venueId(),
					location.name(), location.area(), location.mapUrl(), competition.organiserId(),
					claimedSquad(competitionId, pair.home(), pair.away()), competition.country(), competition.timezone()));
			}
		}
		fixtures.create(specs);
		jdbc.sql("UPDATE games SET visibility='private', competition_location_id=:location WHERE competition_id=:id")
			.param("location", location.id()).param("id", competitionId).update();
		jdbc.sql("UPDATE competitions SET schedule_status='draft', status='draft', updated_at=:now WHERE id=:id")
			.param("now", db(clock.instant())).param("id", competitionId).update();
		audit(competitionId, actorId, "schedule.generated", "competition", competitionId, Map.of("fixtures", specs.size()));
		return schedule(competitionId, actorId);
	}

	@Transactional(readOnly = true)
	public List<CorporateViews.Fixture> schedule(UUID competitionId, UUID actorId) {
		requireScheduleViewer(competitionId, actorId);
		return jdbc.sql("""
				SELECT g.id,g.fixture_round,g.home_team_id,ht.name home_name,g.away_team_id,at.name away_name,
				 g.starts_at,g.duration_minutes,g.status,g.competition_location_id,l.name location_name,
				 o.user_id official_id,u.name official_name,(r.game_id IS NOT NULL) has_result
				FROM games g JOIN teams ht ON ht.id=g.home_team_id JOIN teams at ON at.id=g.away_team_id
				LEFT JOIN competition_locations l ON l.id=g.competition_location_id
				LEFT JOIN fixture_officials o ON o.game_id=g.id LEFT JOIN users u ON u.id=o.user_id
				LEFT JOIN game_results r ON r.game_id=g.id WHERE g.competition_id=:id ORDER BY g.starts_at,g.id
				""").param("id", competitionId).query((rs,n) -> new CorporateViews.Fixture((UUID) rs.getObject("id"),
				rs.getInt("fixture_round"), (UUID) rs.getObject("home_team_id"), rs.getString("home_name"),
				(UUID) rs.getObject("away_team_id"), rs.getString("away_name"), instant(rs,"starts_at"), rs.getInt("duration_minutes"),
				rs.getString("status"), (UUID) rs.getObject("competition_location_id"), rs.getString("location_name"),
				(UUID) rs.getObject("official_id"), rs.getString("official_name"), rs.getBoolean("has_result"))).list();
	}

	@Transactional
	public CorporateViews.Fixture editFixture(UUID competitionId, UUID fixtureId, int round, Instant startsAt,
			int durationMinutes, UUID locationId, UUID actorId) {
		requireManager(competitionId, actorId);
		if (round < 1 || startsAt == null || durationMinutes < 15 || durationMinutes > 480) {
			throw BusinessException.invalid("Choose a round, kick-off and duration for the fixture.");
		}
		var before = jdbc.sql("SELECT starts_at,status FROM games WHERE id=:fixture AND competition_id=:competition FOR UPDATE")
			.param("fixture", fixtureId).param("competition", competitionId)
			.query((rs,n) -> new MoveRow(instant(rs,"starts_at"), rs.getString("status"))).optional()
			.orElseThrow(() -> BusinessException.notFound("That fixture doesn’t exist any more."));
		if (!before.startsAt().isAfter(clock.instant()) || "completed".equals(before.status()) ||
			numberForFixture("SELECT count(*) FROM game_results WHERE game_id=:id", fixtureId) > 0) {
			throw BusinessException.conflict("A fixture cannot move after kick-off or after a result exists.");
		}
		var location = location(competitionId, locationId);
		bookings.releaseForGame(fixtureId);
		jdbc.sql("""
				UPDATE games SET fixture_round=:round,starts_at=:starts,duration_minutes=:duration,
				 competition_location_id=:location,venue_kind=:kind,venue_id=:venue,venue_name=:name,venue_area=:area,
				 pitch_id=:pitch,updated_at=:now WHERE id=:fixture
				""").param("round", round).param("starts", db(startsAt)).param("duration", durationMinutes).param("location", locationId)
			.param("kind", location.venueId()==null ? "unlisted" : "listed").param("venue", location.venueId())
			.param("name", location.name()).param("area", location.area()).param("pitch", location.pitchId())
			.param("now", db(clock.instant())).param("fixture", fixtureId).update();
		var status = jdbc.sql("SELECT schedule_status FROM competitions WHERE id=:id").param("id", competitionId).query(String.class).single();
		if ("published".equals(status) && location.pitchId()!=null) {
			bookings.bookForGame(location.venueId(), location.pitchId(), startsAt, startsAt.plus(durationMinutes, ChronoUnit.MINUTES),
				fixtureId, competition(competitionId).organiserId());
		}
		audit(competitionId, actorId, "fixture.moved", "fixture", fixtureId,
			Map.of("oldStartsAt", before.startsAt().toString(), "newStartsAt", startsAt.toString()));
		var recipients = fixtureRecipients(fixtureId);
		events.publishEvent(new CorporateEvents.FixtureMoved(competitionId, competition(competitionId).name(), fixtureId, recipients,
			"Kick-off is now " + startsAt, actorId));
		return schedule(competitionId, actorId).stream().filter(f -> f.id().equals(fixtureId)).findFirst().orElseThrow();
	}

	@Transactional
	public void assignOfficial(UUID competitionId, UUID fixtureId, UUID officialId, UUID actorId) {
		requireManager(competitionId, actorId);
		jdbc.sql("""
				INSERT INTO fixture_officials (game_id,user_id,assigned_by,assigned_at) VALUES (:game,:official,:actor,:now)
				ON CONFLICT (game_id) DO UPDATE SET user_id=excluded.user_id,assigned_by=excluded.assigned_by,assigned_at=excluded.assigned_at
				""").param("game", fixtureId).param("official", officialId).param("actor", actorId).param("now", db(clock.instant())).update();
		var validation = validateSchedule(competitionId, actorId);
		var officialClash = validation.conflicts().stream().anyMatch(c -> "official-overlap".equals(c.code()) && c.fixtureIds().contains(fixtureId));
		if (officialClash) {
			throw BusinessException.conflict("That official is already assigned to an overlapping fixture.");
		}
		audit(competitionId, actorId, "official.assigned", "fixture", fixtureId, Map.of("officialId", officialId));
	}

	@Transactional(readOnly = true)
	public CorporateViews.Validation validateSchedule(UUID competitionId, UUID actorId) {
		requireManager(competitionId, actorId);
		var conflicts = new ArrayList<CorporateViews.Conflict>();
		var rows = schedule(competitionId, actorId);
		for (int i=0;i<rows.size();i++) for (int j=i+1;j<rows.size();j++) {
			var a=rows.get(i); var b=rows.get(j);
			if (!overlaps(a,b)) continue;
			if (sharesTeam(a,b)) conflicts.add(conflict("team-overlap","A team is scheduled in two matches at the same time.",a.id(),b.id()));
			if (a.locationId()!=null && a.locationId().equals(b.locationId())) conflicts.add(conflict("location-overlap","A pitch is scheduled twice at the same time.",a.id(),b.id()));
			if (a.officialId()!=null && a.officialId().equals(b.officialId())) conflicts.add(conflict("official-overlap","An official is assigned to overlapping fixtures.",a.id(),b.id()));
		}
		rows.stream().filter(f -> f.locationId()==null).forEach(f -> conflicts.add(conflict("missing-location","Choose a location for every fixture.",f.id())));
		for (var fixture : rows) {
			if (fixture.locationId() == null) continue;
			var location = location(competitionId, fixture.locationId());
			if (location.pitchId() == null) continue;
			bookings.problemForGame(location.venueId(), location.pitchId(), fixture.startsAt(),
				fixture.startsAt().plus(fixture.durationMinutes(), ChronoUnit.MINUTES), fixture.id())
				.ifPresent(message -> conflicts.add(conflict("venue-unavailable", message, fixture.id())));
		}
		return new CorporateViews.Validation(conflicts.isEmpty(), List.copyOf(conflicts));
	}

	@Transactional
	public CorporateViews.Validation publishSchedule(UUID competitionId, UUID actorId) {
		var competition = requireManager(competitionId, actorId);
		jdbc.sql("SELECT id FROM competitions WHERE id=:id FOR UPDATE").param("id", competitionId).query(UUID.class).single();
		var validation = validateSchedule(competitionId, actorId);
		if (!validation.valid()) return validation;
		for (var fixture : schedule(competitionId, actorId)) {
			var location = location(competitionId, fixture.locationId());
			if (location.pitchId()!=null) {
				bookings.bookForGame(location.venueId(), location.pitchId(), fixture.startsAt(),
					fixture.startsAt().plus(fixture.durationMinutes(), ChronoUnit.MINUTES), fixture.id(), competition.organiserId());
			}
		}
		jdbc.sql("UPDATE games SET visibility='public' WHERE competition_id=:id").param("id", competitionId).update();
		jdbc.sql("UPDATE competitions SET schedule_status='published',status='running',updated_at=:now WHERE id=:id")
			.param("now", db(clock.instant())).param("id", competitionId).update();
		audit(competitionId, actorId, "schedule.published", "competition", competitionId, Map.of());
		events.publishEvent(new CorporateEvents.SchedulePublished(competitionId, competition.name(), competitionRecipients(competitionId), actorId));
		return validation;
	}

	private CorporateViews.Location defaultLocation(UUID competitionId, CompetitionRow competition) {
		return jdbc.sql("SELECT id,name,area,map_url,venue_id,pitch_id FROM competition_locations WHERE competition_id=:id ORDER BY created_at LIMIT 1")
			.param("id", competitionId).query((rs,n) -> new CorporateViews.Location((UUID)rs.getObject(1),rs.getString(2),rs.getString(3),
				rs.getString(4),(UUID)rs.getObject(5),(UUID)rs.getObject(6))).optional().orElseGet(() -> {
				var id=UUID.randomUUID();
				jdbc.sql("INSERT INTO competition_locations (id,competition_id,name,area,map_url,venue_id,created_at) VALUES (:id,:competition,:name,:area,:map,:venue,:now)")
					.param("id",id).param("competition",competitionId).param("name",competition.venueName()).param("area",competition.venueArea())
					.param("map",competition.mapUrl()).param("venue",competition.venueId()).param("now",db(clock.instant())).update();
				return new CorporateViews.Location(id,competition.venueName(),competition.venueArea(),competition.mapUrl(),competition.venueId(),null);
			});
	}

	private CorporateViews.Location location(UUID competitionId, UUID locationId) {
		return jdbc.sql("SELECT id,name,area,map_url,venue_id,pitch_id FROM competition_locations WHERE id=:location AND competition_id=:competition")
			.param("location",locationId).param("competition",competitionId).query((rs,n) -> new CorporateViews.Location((UUID)rs.getObject(1),
				rs.getString(2),rs.getString(3),rs.getString(4),(UUID)rs.getObject(5),(UUID)rs.getObject(6))).optional()
			.orElseThrow(() -> BusinessException.invalid("Choose one of this competition’s locations."));
	}

	private static boolean overlaps(CorporateViews.Fixture a, CorporateViews.Fixture b) {
		return a.startsAt().isBefore(b.startsAt().plus(b.durationMinutes(),ChronoUnit.MINUTES)) &&
			b.startsAt().isBefore(a.startsAt().plus(a.durationMinutes(),ChronoUnit.MINUTES));
	}
	private static boolean sharesTeam(CorporateViews.Fixture a, CorporateViews.Fixture b) {
		return a.homeTeamId().equals(b.homeTeamId()) || a.homeTeamId().equals(b.awayTeamId()) ||
			a.awayTeamId().equals(b.homeTeamId()) || a.awayTeamId().equals(b.awayTeamId());
	}
	private static CorporateViews.Conflict conflict(String code,String message,UUID... ids) {
		return new CorporateViews.Conflict(code,message,List.of(ids));
	}
	private record TeamRow(UUID id,String name) {}
	private record MoveRow(Instant startsAt,String status) {}


	@Transactional(readOnly = true)
	public List<CorporateViews.Finance> finance(UUID competitionId, UUID actorId) {
		requireManager(competitionId, actorId);
		return jdbc.sql("""
				SELECT e.team_id,t.name,coalesce(f.amount_due,0) amount_due,coalesce(f.status,'unpaid') status,
				 f.method,f.reference,f.paid_at,f.private_note,f.updated_at
				FROM competition_entries e JOIN teams t ON t.id=e.team_id
				LEFT JOIN competition_entry_finance f ON f.competition_id=e.competition_id AND f.team_id=e.team_id
				WHERE e.competition_id=:id ORDER BY t.name
				""").param("id",competitionId).query((rs,n) -> new CorporateViews.Finance((UUID)rs.getObject("team_id"),rs.getString("name"),
				rs.getLong("amount_due"),rs.getString("status"),rs.getString("method"),rs.getString("reference"),instant(rs,"paid_at"),
				rs.getString("private_note"),instant(rs,"updated_at"))).list();
	}

	@Transactional
	public CorporateViews.Finance updateFinance(UUID competitionId, UUID teamId, long amountDue, String status,
			String method, String reference, Instant paidAt, String note, UUID actorId) {
		requireManager(competitionId,actorId);
		if (amountDue<0 || !Set.of("unpaid","paid","waived").contains(status)) {
			throw BusinessException.invalid("Enter an amount and choose unpaid, paid or waived.");
		}
		if (method!=null && !Set.of("bank-transfer","momo","cash","other").contains(method)) {
			throw BusinessException.invalid("Choose how the external payment was made.");
		}
		var now=clock.instant();
		jdbc.sql("""
				INSERT INTO competition_entry_finance (competition_id,team_id,amount_due,status,method,reference,paid_at,private_note,updated_by,updated_at)
				VALUES (:competition,:team,:amount,:status,:method,:reference,:paid,:note,:actor,:now)
				ON CONFLICT (competition_id,team_id) DO UPDATE SET amount_due=excluded.amount_due,status=excluded.status,
				 method=excluded.method,reference=excluded.reference,paid_at=excluded.paid_at,private_note=excluded.private_note,
				 updated_by=excluded.updated_by,updated_at=excluded.updated_at
				""").param("competition",competitionId).param("team",teamId).param("amount",amountDue).param("status",status)
			.param("method",method).param("reference",optional(reference,120)).param("paid",paidAt == null ? null : db(paidAt)).param("note",optional(note,500))
			.param("actor",actorId).param("now",db(now)).update();
		audit(competitionId,actorId,"finance.updated","team",teamId,Map.of("status",status,"amountDue",amountDue));
		return finance(competitionId,actorId).stream().filter(f->f.teamId().equals(teamId)).findFirst().orElseThrow();
	}

	@Transactional
	public CorporateViews.Announcement announce(UUID competitionId,String audience,UUID teamId,String title,String body,
			boolean acknowledgement,UUID actorId) {
		var competition=requireManager(competitionId,actorId);
		if (!Set.of("everyone","staff","team").contains(audience) || ("team".equals(audience)!=(teamId!=null))) {
			throw BusinessException.invalid("Choose everyone, staff or one team.");
		}
		var id=UUID.randomUUID(); var now=clock.instant();
		var cleanTitle=text(title,100,"Give the announcement a title."); var cleanBody=text(body,2000,"Write the announcement.");
		jdbc.sql("""
				INSERT INTO competition_announcements (id,competition_id,audience,team_id,title,body,acknowledgement,published_by,published_at)
				VALUES (:id,:competition,:audience,:team,:title,:body,:ack,:actor,:now)
				""").param("id",id).param("competition",competitionId).param("audience",audience).param("team",teamId)
			.param("title",cleanTitle).param("body",cleanBody).param("ack",acknowledgement).param("actor",actorId).param("now",db(now)).update();
		var recipients=announcementRecipients(competitionId,audience,teamId);
		for (var recipient:recipients) jdbc.sql("INSERT INTO announcement_recipients (announcement_id,user_id) VALUES (:id,:user) ON CONFLICT DO NOTHING")
			.param("id",id).param("user",recipient).update();
		audit(competitionId,actorId,"announcement.published","announcement",id,Map.of("audience",audience));
		events.publishEvent(new CorporateEvents.AnnouncementPublished(competitionId,competition.name(),id,cleanTitle,cleanBody,recipients,actorId));
		return announcement(id,competitionId,actorId);
	}

	@Transactional(readOnly = true)
	public List<CorporateViews.Announcement> announcements(UUID competitionId,UUID actorId) {
		requireScheduleViewer(competitionId,actorId);
		return jdbc.sql("""
				SELECT a.*,count(r.user_id) recipients,count(r.acknowledged_at) acknowledged
				FROM competition_announcements a LEFT JOIN announcement_recipients r ON r.announcement_id=a.id
				WHERE a.competition_id=:id GROUP BY a.id ORDER BY a.published_at DESC
				""").param("id",competitionId).query((rs,n)->announcement(rs)).list();
	}

	@Transactional
	public void acknowledge(UUID announcementId,UUID actorId) {
		var changed=jdbc.sql("UPDATE announcement_recipients SET acknowledged_at=coalesce(acknowledged_at,:now) WHERE announcement_id=:id AND user_id=:user")
			.param("now",db(clock.instant())).param("id",announcementId).param("user",actorId).update();
		if(changed==0) throw BusinessException.notFound("That announcement isn’t for you.");
	}

	private CorporateViews.Announcement announcement(UUID id,UUID competitionId,UUID actorId) {
		return announcements(competitionId,actorId).stream().filter(a->a.id().equals(id)).findFirst().orElseThrow();
	}
	private CorporateViews.Announcement announcement(ResultSet rs) throws SQLException {
		var message=rs.getString("title")+"%0A%0A"+rs.getString("body");
		return new CorporateViews.Announcement((UUID)rs.getObject("id"),rs.getString("audience"),(UUID)rs.getObject("team_id"),
			rs.getString("title"),rs.getString("body"),rs.getBoolean("acknowledgement"),(UUID)rs.getObject("published_by"),
			instant(rs,"published_at"),rs.getInt("recipients"),rs.getInt("acknowledged"),"https://wa.me/?text="+message.replace(" ","%20"));
	}

	@Transactional
	public CorporateViews.MatchSheet saveMatchSheet(UUID competitionId,UUID gameId,SheetInput input,UUID actorId) {
		requireOfficialOrManager(competitionId,gameId,actorId);
		if (input.homeScore() < 0 || input.awayScore() < 0) throw BusinessException.invalid("Scores cannot be negative.");
		var status = jdbc.sql("SELECT status FROM fixture_match_sheets WHERE game_id=:game FOR UPDATE")
			.param("game", gameId).query(String.class).optional();
		if (status.filter("submitted"::equals).isPresent())
			throw BusinessException.conflict("A submitted result can only be changed through a correction with a reason.");
		var now=clock.instant();
		jdbc.sql("""
				INSERT INTO fixture_match_sheets (game_id,home_score,away_score,notes,status,saved_by,saved_at)
				VALUES (:game,:home,:away,:notes,'draft',:actor,:now)
				ON CONFLICT (game_id) DO UPDATE SET home_score=excluded.home_score,away_score=excluded.away_score,
				 notes=excluded.notes,saved_by=excluded.saved_by,saved_at=excluded.saved_at
				WHERE fixture_match_sheets.status='draft'
				""").param("game",gameId).param("home",input.homeScore()).param("away",input.awayScore())
			.param("notes",optional(input.notes(),2000)).param("actor",actorId).param("now",db(now)).update();
		jdbc.sql("DELETE FROM match_sheet_players WHERE game_id=:game").param("game",gameId).update();
		jdbc.sql("DELETE FROM match_sheet_cards WHERE game_id=:game").param("game",gameId).update();
		for(var player:input.players()) {
			if (!Set.of("starter", "substitute", "did-not-play").contains(player.participation()) || player.goals() < 0 || player.assists() < 0)
				throw BusinessException.invalid("Choose valid participation, goal and assist values.");
			var rosterTeam=jdbc.sql("""
					SELECT r.team_id FROM roster_members r JOIN games g ON g.id=:game
					WHERE r.id=:member AND r.competition_id=:competition AND r.eligibility_state='approved'
					AND r.team_id IN (g.home_team_id,g.away_team_id)
					""").param("game",gameId).param("member",player.rosterMemberId()).param("competition",competitionId).query(UUID.class).optional()
				.orElseThrow(() -> BusinessException.invalid("Only approved roster members can appear on the match sheet."));
			if (!rosterTeam.equals(player.teamId())) throw BusinessException.invalid("A match-sheet player must stay with their approved team.");
			jdbc.sql("""
					INSERT INTO match_sheet_players (game_id,roster_member_id,team_id,participation,checked_in,goals,assists)
					VALUES (:game,:member,:team,:participation,:checked,:goals,:assists)
					""").param("game",gameId).param("member",player.rosterMemberId()).param("team",player.teamId())
				.param("participation",player.participation()).param("checked",player.checkedIn()).param("goals",player.goals()).param("assists",player.assists()).update();
		}
		for(var card:input.cards()) {
			if (!Set.of("yellow", "red").contains(card.colour()) || (card.minute() != null && (card.minute() < 0 || card.minute() > 480)))
				throw BusinessException.invalid("Choose a valid card colour and minute.");
			var eligible = jdbc.sql("""
				SELECT count(*) FROM roster_members r JOIN games g ON g.id=:game WHERE r.id=:member
				AND r.competition_id=:competition AND r.eligibility_state='approved' AND r.team_id IN(g.home_team_id,g.away_team_id)
				""").param("game", gameId).param("member", card.rosterMemberId()).param("competition", competitionId)
				.query(Integer.class).single();
			if (eligible == 0) throw BusinessException.invalid("Cards can only be recorded for approved players in this fixture.");
			jdbc.sql("INSERT INTO match_sheet_cards (id,game_id,roster_member_id,colour,minute,note) VALUES (:id,:game,:member,:colour,:minute,:note)")
				.param("id",UUID.randomUUID()).param("game",gameId).param("member",card.rosterMemberId()).param("colour",card.colour())
				.param("minute",card.minute()).param("note",optional(card.note(),300)).update();
		}
		return matchSheet(competitionId,gameId,actorId);
	}

	@Transactional
	public CorporateViews.MatchSheet submitMatchSheet(UUID competitionId,UUID gameId,SheetInput input,UUID actorId) {
		var existing=jdbc.sql("SELECT status FROM fixture_match_sheets WHERE game_id=:game").param("game",gameId).query(String.class).optional();
		if(existing.filter("submitted"::equals).isPresent()) return matchSheet(competitionId,gameId,actorId);
		saveMatchSheet(competitionId,gameId,input,actorId);
		var players=jdbc.sql("""
				SELECT r.user_id,p.participation,p.goals,p.assists,p.team_id,g.home_team_id
				FROM match_sheet_players p JOIN roster_members r ON r.id=p.roster_member_id JOIN games g ON g.id=p.game_id
				WHERE p.game_id=:game AND r.user_id IS NOT NULL
				""").param("game",gameId).query((rs,n)->new OfficialResults.Player((UUID)rs.getObject("user_id"),
				"did-not-play".equals(rs.getString("participation"))?"absent":((UUID)rs.getObject("team_id")).equals((UUID)rs.getObject("home_team_id"))?"home":"away",
				rs.getInt("goals"),rs.getInt("assists"))).list();
		results.record(gameId,input.homeScore(),input.awayScore(),players,actorId);
		var now=clock.instant();
		jdbc.sql("UPDATE fixture_match_sheets SET status='submitted',submitted_by=:actor,submitted_at=:now WHERE game_id=:game")
			.param("actor",actorId).param("now",db(now)).param("game",gameId).update();
		audit(competitionId,actorId,"match-sheet.submitted","fixture",gameId,Map.of("homeScore",input.homeScore(),"awayScore",input.awayScore()));
		return matchSheet(competitionId,gameId,actorId);
	}

	@Transactional
	public CorporateViews.MatchSheet correctMatchSheet(UUID competitionId,UUID gameId,SheetInput input,String reason,UUID actorId) {
		requireManager(competitionId,actorId); text(reason,500,"Give a reason for correcting the official result.");
		var before=matchSheet(competitionId,gameId,actorId);
		var next=before.version()+1;
		jdbc.sql("INSERT INTO match_sheet_revisions (id,game_id,version,snapshot,reason,corrected_by,corrected_at) VALUES (:id,:game,:version,CAST(:snapshot AS jsonb),:reason,:actor,:now)")
			.param("id",UUID.randomUUID()).param("game",gameId).param("version",before.version()).param("snapshot",json(before))
			.param("reason",reason.strip()).param("actor",actorId).param("now",db(clock.instant())).update();
		jdbc.sql("UPDATE fixture_match_sheets SET status='draft',submitted_by=NULL,submitted_at=NULL,version=:version WHERE game_id=:game")
			.param("version",next).param("game",gameId).update();
		var corrected=submitMatchSheet(competitionId,gameId,input,actorId);
		audit(competitionId,actorId,"match-sheet.corrected","fixture",gameId,Map.of("reason",reason.strip(),"version",next));
		return corrected;
	}

	@Transactional(readOnly=true)
	public CorporateViews.MatchSheet matchSheet(UUID competitionId,UUID gameId,UUID actorId) {
		requireOfficialOrManager(competitionId,gameId,actorId);
		var sheet=jdbc.sql("SELECT * FROM fixture_match_sheets WHERE game_id=:game").param("game",gameId).query((rs,n)->new SheetRow(
			rs.getInt("home_score"),rs.getInt("away_score"),rs.getString("notes"),rs.getString("status"),(UUID)rs.getObject("saved_by"),
			instant(rs,"saved_at"),(UUID)rs.getObject("submitted_by"),instant(rs,"submitted_at"),rs.getInt("version"))).optional()
			.orElse(new SheetRow(0,0,null,"draft",actorId,clock.instant(),null,null,1));
		var players=jdbc.sql("""
				SELECT p.roster_member_id,p.team_id,r.display_name,r.user_id,p.participation,p.checked_in,p.goals,p.assists
				FROM match_sheet_players p JOIN roster_members r ON r.id=p.roster_member_id WHERE p.game_id=:game ORDER BY r.display_name
				""").param("game",gameId).query((rs,n)->new CorporateViews.MatchPlayer((UUID)rs.getObject(1),(UUID)rs.getObject(2),rs.getString(3),
				(UUID)rs.getObject(4),rs.getString(5),rs.getBoolean(6),rs.getInt(7),rs.getInt(8))).list();
		var cards=jdbc.sql("SELECT id,roster_member_id,colour,minute,note FROM match_sheet_cards WHERE game_id=:game ORDER BY minute NULLS LAST")
			.param("game",gameId).query((rs,n)->new CorporateViews.Card((UUID)rs.getObject(1),(UUID)rs.getObject(2),rs.getString(3),
				(Integer)rs.getObject(4),rs.getString(5))).list();
		return new CorporateViews.MatchSheet(gameId,sheet.home(),sheet.away(),sheet.notes(),sheet.status(),sheet.savedBy(),sheet.savedAt(),
			sheet.submittedBy(),sheet.submittedAt(),sheet.version(),players,cards);
	}

	public record SheetInput(int homeScore,int awayScore,String notes,List<PlayerInput> players,List<CardInput> cards) {
		public SheetInput { players=players==null?List.of():List.copyOf(players); cards=cards==null?List.of():List.copyOf(cards); }
	}
	public record PlayerInput(UUID rosterMemberId,UUID teamId,String participation,boolean checkedIn,int goals,int assists) {}
	public record CardInput(UUID rosterMemberId,String colour,Integer minute,String note) {}
	private record SheetRow(int home,int away,String notes,String status,UUID savedBy,Instant savedAt,UUID submittedBy,Instant submittedAt,int version) {}

	@Transactional
	public CorporateViews.RoleInvitation inviteRole(UUID competitionId,String role,UUID teamId,UUID actorId) {
		requireManager(competitionId,actorId);
		if(!Set.of("manager","team-manager").contains(role) || ("team-manager".equals(role)!=(teamId!=null)))
			throw BusinessException.invalid("Choose a competition manager or a team manager.");
		var id=UUID.randomUUID(); var raw=token(); var now=clock.instant(); var expires=now.plus(7,ChronoUnit.DAYS);
		jdbc.sql("INSERT INTO competition_role_invitations (id,competition_id,team_id,role,token_hash,invited_by,expires_at,created_at) VALUES (:id,:competition,:team,:role,:hash,:actor,:expires,:now)")
			.param("id",id).param("competition",competitionId).param("team",teamId).param("role",role).param("hash",hash(raw))
			.param("actor",actorId).param("expires",db(expires)).param("now",db(now)).update();
		audit(competitionId,actorId,"staff.invited","invitation",id,Map.of("role",role));
		return new CorporateViews.RoleInvitation(id,role,teamId,expires,"/competitions/join?token="+raw);
	}

	@Transactional
	public UUID acceptRole(String raw,UUID actorId) {
		var invite=jdbc.sql("SELECT id,competition_id,team_id,role FROM competition_role_invitations WHERE token_hash=:hash AND accepted_at IS NULL AND expires_at>:now FOR UPDATE")
			.param("hash",hash(text(raw,200,"That invitation link isn’t valid."))).param("now",db(clock.instant()))
			.query((rs,n)->new RoleInvite((UUID)rs.getObject(1),(UUID)rs.getObject(2),(UUID)rs.getObject(3),rs.getString(4))).optional()
			.orElseThrow(()->BusinessException.conflict("That invitation has expired or was already used."));
		var now=clock.instant();
		if("manager".equals(invite.role())) jdbc.sql("INSERT INTO competition_staff (competition_id,user_id,role,added_by,added_at) VALUES (:competition,:user,'manager',:user,:now) ON CONFLICT DO NOTHING")
			.param("competition",invite.competitionId()).param("user",actorId).param("now",db(now)).update();
		else jdbc.sql("INSERT INTO competition_entry_managers (competition_id,team_id,user_id,added_by,added_at) VALUES (:competition,:team,:user,:user,:now) ON CONFLICT DO NOTHING")
			.param("competition",invite.competitionId()).param("team",invite.teamId()).param("user",actorId).param("now",db(now)).update();
		jdbc.sql("UPDATE competition_role_invitations SET accepted_by=:user,accepted_at=:now WHERE id=:id")
			.param("user",actorId).param("now",db(now)).param("id",invite.id()).update();
		audit(invite.competitionId(),actorId,"staff.joined","membership",actorId,Map.of("role",invite.role()));
		return invite.competitionId();
	}
	private record RoleInvite(UUID id,UUID competitionId,UUID teamId,String role) {}

	@Transactional(readOnly=true)
	public List<CorporateViews.AuditEvent> audit(UUID competitionId,UUID actorId) {
		requireManager(competitionId,actorId);
		return jdbc.sql("SELECT id,actor_id,event_type,subject_type,subject_id,details::text,occurred_at FROM audit_events WHERE competition_id=:id ORDER BY occurred_at DESC LIMIT 500")
			.param("id",competitionId).query((rs,n)->new CorporateViews.AuditEvent((UUID)rs.getObject(1),(UUID)rs.getObject(2),rs.getString(3),
				rs.getString(4),(UUID)rs.getObject(5),rs.getString(6),instant(rs,"occurred_at"))).list();
	}

	public void authorizeManager(UUID competitionId, UUID userId) { requireManager(competitionId, userId); }

	public boolean canManage(UUID competitionId, UUID userId) {
		return jdbc.sql("SELECT count(*) FROM competition_staff WHERE competition_id=:competition AND user_id=:user AND role='manager'")
			.param("competition",competitionId).param("user",userId).query(Integer.class).single()>0;
	}

	private CompetitionRow requireManager(UUID competitionId,UUID userId) {
		var competition=competition(competitionId);
		var allowed=jdbc.sql("""
				SELECT count(*) FROM competitions c WHERE c.id=:competition AND (
				 c.organiser_id=:user OR EXISTS (SELECT 1 FROM competition_organisers x WHERE x.competition_id=c.id AND x.user_id=:user)
				 OR EXISTS (SELECT 1 FROM competition_staff s WHERE s.competition_id=c.id AND s.user_id=:user AND s.role='manager')
				 OR EXISTS (SELECT 1 FROM organisation_memberships m WHERE m.organisation_id=c.organisation_id AND m.user_id=:user))
				""").param("competition",competitionId).param("user",userId).query(Integer.class).single()>0;
		if(!allowed) throw BusinessException.notFound("That competition doesn’t exist any more.");
		return competition;
	}
	private void requireTeamManager(UUID competitionId,UUID teamId,UUID userId) {
		try { requireManager(competitionId,userId); return; } catch(BusinessException ignored) {}
		var allowed=jdbc.sql("SELECT count(*) FROM competition_entry_managers WHERE competition_id=:competition AND team_id=:team AND user_id=:user")
			.param("competition",competitionId).param("team",teamId).param("user",userId).query(Integer.class).single()>0;
		if(!allowed) throw BusinessException.notFound("That team roster doesn’t exist any more.");
	}
	private void requireScheduleViewer(UUID competitionId,UUID userId) {
		try { requireManager(competitionId,userId); return; } catch(BusinessException ignored) {}
		var allowed=jdbc.sql("""
				SELECT count(*) FROM fixture_officials f JOIN games g ON g.id=f.game_id WHERE g.competition_id=:competition AND f.user_id=:user
				""").param("competition",competitionId).param("user",userId).query(Integer.class).single()>0;
		if(!allowed) throw BusinessException.notFound("That competition doesn’t exist any more.");
	}
	private void requireOfficialOrManager(UUID competitionId,UUID gameId,UUID userId) {
		try { requireManager(competitionId,userId); return; } catch(BusinessException ignored) {}
		var allowed=jdbc.sql("SELECT count(*) FROM fixture_officials f JOIN games g ON g.id=f.game_id WHERE f.game_id=:game AND g.competition_id=:competition AND f.user_id=:user")
			.param("game",gameId).param("competition",competitionId).param("user",userId).query(Integer.class).single()>0;
		if(!allowed) throw BusinessException.notFound("That match sheet doesn’t exist any more.");
	}

	private CompetitionRow competition(UUID id) {
		return jdbc.sql("""
				SELECT id,organisation_id,name,sport,format,organiser_id,starts_at,duration_minutes,country,timezone,schedule_status,
				 venue_id,venue_name,venue_area,map_url FROM competitions WHERE id=:id AND organisation_id IS NOT NULL
				""").param("id",id).query((rs,n)->new CompetitionRow((UUID)rs.getObject("id"),(UUID)rs.getObject("organisation_id"),rs.getString("name"),
				rs.getString("sport"),rs.getString("format"),(UUID)rs.getObject("organiser_id"),instant(rs,"starts_at"),rs.getInt("duration_minutes"),
				rs.getString("country"),rs.getString("timezone"),rs.getString("schedule_status"),(UUID)rs.getObject("venue_id"),rs.getString("venue_name"),
				rs.getString("venue_area"),rs.getString("map_url"))).optional()
			.orElseThrow(()->BusinessException.notFound("That corporate competition doesn’t exist any more."));
	}
	private record CompetitionRow(UUID id,UUID organisationId,String name,String sport,String format,UUID organiserId,Instant startsAt,
		int durationMinutes,String country,String timezone,String scheduleStatus,UUID venueId,String venueName,String venueArea,String mapUrl) {}

	private List<UUID> announcementRecipients(UUID competitionId,String audience,UUID teamId) {
		var sql=switch(audience) {
			case "staff" -> "SELECT user_id FROM competition_staff WHERE competition_id=:competition UNION SELECT organiser_id FROM competitions WHERE id=:competition UNION SELECT user_id FROM competition_organisers WHERE competition_id=:competition";
			case "team" -> "SELECT user_id FROM roster_members WHERE competition_id=:competition AND team_id=:team AND user_id IS NOT NULL UNION SELECT user_id FROM competition_entry_managers WHERE competition_id=:competition AND team_id=:team";
			default -> "SELECT user_id FROM roster_members WHERE competition_id=:competition AND user_id IS NOT NULL UNION SELECT user_id FROM competition_staff WHERE competition_id=:competition UNION SELECT organiser_id FROM competitions WHERE id=:competition UNION SELECT user_id FROM competition_organisers WHERE competition_id=:competition";
		};
		var query=jdbc.sql(sql).param("competition",competitionId); if("team".equals(audience)) query=query.param("team",teamId);
		return query.query(UUID.class).list();
	}
	private List<UUID> competitionRecipients(UUID competitionId) { return announcementRecipients(competitionId,"everyone",null); }
	private List<UUID> fixtureRecipients(UUID gameId) {
		return jdbc.sql("""
				SELECT r.user_id FROM games g JOIN roster_members r ON r.competition_id=g.competition_id AND r.team_id IN(g.home_team_id,g.away_team_id)
				WHERE g.id=:game AND r.user_id IS NOT NULL UNION SELECT user_id FROM fixture_officials WHERE game_id=:game
				""").param("game",gameId).query(UUID.class).list();
	}
	private int numberForFixture(String sql,UUID id) { return jdbc.sql(sql).param("id",id).query(Integer.class).single(); }

	private void audit(UUID competitionId,UUID actorId,String event,String subject,UUID subjectId,Map<String,?> details) {
		var organisation=competition(competitionId).organisationId();
		jdbc.sql("""
				INSERT INTO audit_events (id,organisation_id,competition_id,actor_id,event_type,subject_type,subject_id,details,occurred_at)
				VALUES (:id,:organisation,:competition,:actor,:event,:subject,:subjectId,CAST(:details AS jsonb),:now)
				""").param("id",UUID.randomUUID()).param("organisation",organisation).param("competition",competitionId).param("actor",actorId)
			.param("event",event).param("subject",subject).param("subjectId",subjectId).param("details",json(details)).param("now",db(clock.instant())).update();
	}
	private String json(Object value) { return json.writeValueAsString(value); }
	private static OffsetDateTime db(Instant value) { return value.atOffset(ZoneOffset.UTC); }
	private static Instant instant(ResultSet rs,String column) throws SQLException { var value=rs.getTimestamp(column); return value==null?null:value.toInstant(); }
	private static String text(String value,int max,String message) { var clean=value==null?"":value.strip(); if(clean.isEmpty()||clean.length()>max) throw BusinessException.invalid(message); return clean; }
	private static String optional(String value,int max) { if(value==null||value.isBlank()) return null; var clean=value.strip(); if(clean.length()>max) throw BusinessException.invalid("Keep that field under "+max+" characters."); return clean; }
	private static String token() { var bytes=new byte[24]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
	private static String hash(String value) { try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
}
