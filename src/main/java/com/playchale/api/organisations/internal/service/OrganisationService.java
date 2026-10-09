package com.playchale.api.organisations.internal.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.playchale.api.organisations.api.OrganisationAccess;
import com.playchale.api.shared.error.BusinessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganisationService implements OrganisationAccess {

	private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
	private static final Pattern COLOUR = Pattern.compile("^#[0-9a-fA-F]{6}$");
	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcClient jdbc;
	private final Clock clock;

	public OrganisationService(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<OrganisationViews.Organisation> mine(UUID userId) {
		return jdbc.sql("""
				SELECT o.id, o.name, o.slug, o.country, o.primary_colour, o.corporate_enabled,
				       m.role, o.logo_version, o.created_at
				FROM organisations o JOIN organisation_memberships m ON m.organisation_id = o.id
				WHERE m.user_id = :user ORDER BY o.name
				""").param("user", userId).query((rs, row) -> organisation(rs)).list();
	}

	@Transactional
	public OrganisationViews.Workspace create(String name, String slug, String country, String colour, UUID userId) {
		var cleanName = required(name, 80, "Give the organisation a name.");
		var cleanSlug = slug == null ? "" : slug.strip().toLowerCase(Locale.ROOT);
		if (!SLUG.matcher(cleanSlug).matches() || cleanSlug.length() > 60) {
			throw BusinessException.invalid("Use letters, numbers and hyphens for the workspace address.");
		}
		var cleanCountry = country == null ? "GH" : country.strip().toUpperCase(Locale.ROOT);
		if (cleanCountry.length() != 2) {
			throw BusinessException.invalid("Pick a country.");
		}
		var cleanColour = colour == null ? "#16332d" : colour.strip();
		if (!COLOUR.matcher(cleanColour).matches()) {
			throw BusinessException.invalid("Pick a six-digit brand colour.");
		}
		if (jdbc.sql("SELECT count(*) FROM organisations WHERE lower(slug) = lower(:slug)")
				.param("slug", cleanSlug).query(Integer.class).single() > 0) {
			throw BusinessException.conflict("That workspace address is already taken.");
		}
		var id = UUID.randomUUID();
		var now = clock.instant();
		jdbc.sql("""
				INSERT INTO organisations (id, name, slug, country, primary_colour, corporate_enabled, created_by, created_at, updated_at)
				VALUES (:id, :name, :slug, :country, :colour, false, :user, :now, :now)
				""").param("id", id).param("name", cleanName).param("slug", cleanSlug).param("country", cleanCountry)
			.param("colour", cleanColour).param("user", userId).param("now", db(now)).update();
		jdbc.sql("""
				INSERT INTO organisation_memberships (organisation_id, user_id, role, created_at, created_by)
				VALUES (:id, :user, 'owner', :now, :user)
				""").param("id", id).param("user", userId).param("now", db(now)).update();
		audit(id, null, userId, "organisation.created", "organisation", id, "{\"role\":\"owner\"}");
		return get(id, userId);
	}

	@Transactional(readOnly = true)
	public OrganisationViews.Workspace get(UUID id, UUID userId) {
		var role = role(id, userId);
		var organisation = jdbc.sql("""
				SELECT o.id, o.name, o.slug, o.country, o.primary_colour, o.corporate_enabled,
				       :role AS role, o.logo_version, o.created_at
				FROM organisations o WHERE o.id = :id
				""").param("role", role).param("id", id).query((rs, row) -> organisation(rs)).optional()
			.orElseThrow(() -> BusinessException.notFound("That organisation doesn’t exist any more."));
		var members = jdbc.sql("""
				SELECT m.user_id, u.name, u.handle, coalesce(u.avatar_url, u.avatar_seed) AS avatar, m.role, m.created_at
				FROM organisation_memberships m JOIN users u ON u.id = m.user_id
				WHERE m.organisation_id = :id ORDER BY CASE m.role WHEN 'owner' THEN 0 ELSE 1 END, u.name
				""").param("id", id).query((rs, row) -> new OrganisationViews.Member(
				(UUID) rs.getObject("user_id"), rs.getString("name"), rs.getString("handle"), rs.getString("avatar"),
				rs.getString("role"), rs.getTimestamp("created_at").toInstant())).list();
		var invitations = "owner".equals(role) ? jdbc.sql("""
				SELECT id, role, expires_at, created_at FROM organisation_invitations
				WHERE organisation_id = :id AND accepted_at IS NULL AND expires_at > :now ORDER BY created_at DESC
				""").param("id", id).param("now", db(clock.instant())).query((rs, row) -> new OrganisationViews.Invitation(
				(UUID) rs.getObject("id"), rs.getString("role"), rs.getTimestamp("expires_at").toInstant(),
				rs.getTimestamp("created_at").toInstant(), null)).list() : List.<OrganisationViews.Invitation>of();
		return new OrganisationViews.Workspace(organisation, members, invitations);
	}

	@Transactional
	public OrganisationViews.Workspace update(UUID id, String name, String colour, Boolean enabled, UUID userId) {
		requireOwner(id, userId);
		var cleanName = required(name, 80, "Give the organisation a name.");
		var cleanColour = required(colour, 7, "Pick a brand colour.");
		if (!COLOUR.matcher(cleanColour).matches()) {
			throw BusinessException.invalid("Pick a six-digit brand colour.");
		}
		jdbc.sql("""
				UPDATE organisations SET name = :name, primary_colour = :colour,
				corporate_enabled = coalesce(:enabled, corporate_enabled), updated_at = :now WHERE id = :id
				""").param("name", cleanName).param("colour", cleanColour).param("enabled", enabled)
			.param("now", db(clock.instant())).param("id", id).update();
		audit(id, null, userId, "organisation.updated", "organisation", id, "{}");
		return get(id, userId);
	}

	@Transactional
	public OrganisationViews.Invitation invite(UUID id, String role, UUID userId) {
		requireOwner(id, userId);
		if (!List.of("admin", "official").contains(role)) {
			throw BusinessException.invalid("Invite them as an admin or as a match official.");
		}
		var bytes = new byte[24];
		RANDOM.nextBytes(bytes);
		var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		var inviteId = UUID.randomUUID();
		var now = clock.instant();
		var expiry = now.plus(Duration.ofDays(7));
		jdbc.sql("""
				INSERT INTO organisation_invitations (id, organisation_id, role, token_hash, invited_by, expires_at, created_at)
				VALUES (:invite, :organisation, :role, :hash, :user, :expiry, :now)
				""").param("invite", inviteId).param("organisation", id).param("role", role).param("hash", hash(token))
			.param("user", userId).param("expiry", db(expiry)).param("now", db(now)).update();
		audit(id, null, userId, "membership.invited", "invitation", inviteId, "{\"role\":\"%s\"}".formatted(role));
		return new OrganisationViews.Invitation(inviteId, role, expiry, now, "/organisations/join?token=" + token);
	}

	@Transactional
	public OrganisationViews.Workspace accept(String token, UUID userId) {
		if (token == null || token.isBlank()) {
			throw BusinessException.invalid("That invitation link isn’t valid.");
		}
		var row = jdbc.sql("""
				SELECT id, organisation_id, role FROM organisation_invitations
				WHERE token_hash = :hash AND accepted_at IS NULL AND expires_at > :now FOR UPDATE
				""").param("hash", hash(token.strip())).param("now", db(clock.instant()))
			.query((rs, n) -> new InviteRow((UUID) rs.getObject("id"), (UUID) rs.getObject("organisation_id"), rs.getString("role")))
			.optional().orElseThrow(() -> BusinessException.conflict("That invitation has expired or was already used."));
		var now = clock.instant();
		jdbc.sql("""
				INSERT INTO organisation_memberships (organisation_id, user_id, role, created_at, created_by)
				VALUES (:organisation, :user, :role, :now, :user)
				ON CONFLICT (organisation_id, user_id) DO NOTHING
				""").param("organisation", row.organisationId()).param("user", userId).param("role", row.role()).param("now", db(now)).update();
		jdbc.sql("UPDATE organisation_invitations SET accepted_by = :user, accepted_at = :now WHERE id = :id")
			.param("user", userId).param("now", db(now)).param("id", row.id()).update();
		audit(row.organisationId(), null, userId, "membership.accepted", "membership", userId, "{\"role\":\"%s\"}".formatted(row.role()));
		return get(row.organisationId(), userId);
	}

	@Transactional
	public OrganisationViews.Workspace removeMember(UUID organisationId, UUID memberId, UUID userId) {
		requireOwner(organisationId, userId);
		if (memberId.equals(userId)) {
			throw BusinessException.conflict("Transfer ownership before leaving this organisation.");
		}
		var changed = jdbc.sql("DELETE FROM organisation_memberships WHERE organisation_id = :organisation AND user_id = :member AND role <> 'owner'")
			.param("organisation", organisationId).param("member", memberId).update();
		if (changed == 0) {
			throw BusinessException.notFound("That person is no longer in this organisation.");
		}
		audit(organisationId, null, userId, "membership.removed", "membership", memberId, "{}");
		return get(organisationId, userId);
	}

	public String role(UUID organisationId, UUID userId) {
		return jdbc.sql("SELECT role FROM organisation_memberships WHERE organisation_id = :organisation AND user_id = :user")
			.param("organisation", organisationId).param("user", userId).query(String.class).optional()
			.orElseThrow(() -> BusinessException.notFound("That organisation doesn’t exist any more."));
	}

	/** The two seats that run a workspace. An official holds a seat but runs nothing. */
	private static final List<String> RUNS_IT = List.of("owner", "admin");

	@Override
	public void requireAdmin(UUID organisationId, UUID userId) {
		if (!RUNS_IT.contains(role(organisationId, userId))) {
			throw BusinessException.notFound("That organisation doesn’t exist any more.");
		}
	}

	@Override
	public boolean corporateEnabled(UUID organisationId) {
		return jdbc.sql("SELECT corporate_enabled FROM organisations WHERE id = :id").param("id", organisationId)
			.query(Boolean.class).optional().orElse(false);
	}

	@Override
	public boolean isAdmin(UUID organisationId, UUID userId) {
		if (organisationId == null || userId == null) return false;
		return jdbc.sql("SELECT count(*) FROM organisation_memberships WHERE organisation_id=:organisation AND user_id=:user AND role IN (:roles)")
			.param("organisation",organisationId).param("user",userId).param("roles",RUNS_IT).query(Integer.class).single()>0;
	}

	@Override
	public Optional<String> roleOf(UUID organisationId, UUID userId) {
		if (organisationId == null || userId == null) return Optional.empty();
		return jdbc.sql("SELECT role FROM organisation_memberships WHERE organisation_id = :organisation AND user_id = :user")
			.param("organisation", organisationId).param("user", userId).query(String.class).optional();
	}

	@Override
	public OrganisationAccess.Brand brand(UUID organisationId) {
		return jdbc.sql("SELECT id,name,primary_colour,logo_version FROM organisations WHERE id=:id").param("id",organisationId)
			.query((rs,n)->new OrganisationAccess.Brand((UUID)rs.getObject(1),rs.getString(2),rs.getString(3),
				rs.getInt(4)==0?null:"/organisations/%s/logo?v=%d".formatted(organisationId,rs.getInt(4)))).optional().orElse(null);
	}

	@Transactional
	public void logo(UUID organisationId, byte[] image, String contentType, UUID actorId) {
		requireOwner(organisationId,actorId);
		var type=contentType==null?"":contentType.split(";")[0].strip().toLowerCase(Locale.ROOT);
		var valid=("image/png".equals(type)&&image!=null&&image.length>4&&image[0]==(byte)0x89&&image[1]=='P')
			||("image/jpeg".equals(type)&&image!=null&&image.length>3&&image[0]==(byte)0xff&&image[1]==(byte)0xd8)
			||("image/webp".equals(type)&&image!=null&&image.length>4&&image[0]=='R'&&image[1]=='I');
		if(!valid||image.length>256*1024) throw BusinessException.invalid("Use a PNG, JPEG or WebP logo under 256 KB.");
		jdbc.sql("UPDATE organisations SET logo=:logo,logo_content_type=:type,logo_version=logo_version+1,updated_at=:now WHERE id=:id")
			.param("logo",image).param("type",type).param("now",db(clock.instant())).param("id",organisationId).update();
		audit(organisationId,null,actorId,"organisation.logo-updated","organisation",organisationId,"{}");
	}

	public Logo logo(UUID organisationId) {
		return jdbc.sql("SELECT logo,logo_content_type FROM organisations WHERE id=:id AND logo IS NOT NULL").param("id",organisationId)
			.query((rs,n)->new Logo(rs.getBytes(1),rs.getString(2))).optional().orElseThrow(()->BusinessException.notFound("That logo doesn’t exist."));
	}
	public record Logo(byte[] bytes,String contentType) { }

	public void requireOwner(UUID organisationId, UUID userId) {
		if (!"owner".equals(role(organisationId, userId))) {
			throw BusinessException.notFound("That organisation doesn’t exist any more.");
		}
	}

	public void audit(UUID organisationId, UUID competitionId, UUID actorId, String event, String subject, UUID subjectId, String json) {
		jdbc.sql("""
				INSERT INTO audit_events (id, organisation_id, competition_id, actor_id, event_type, subject_type, subject_id, details, occurred_at)
				VALUES (:id, :organisation, :competition, :actor, :event, :subject, :subjectId, CAST(:details AS jsonb), :now)
				""").param("id", UUID.randomUUID()).param("organisation", organisationId).param("competition", competitionId)
			.param("actor", actorId).param("event", event).param("subject", subject).param("subjectId", subjectId)
			.param("details", json).param("now", db(clock.instant())).update();
	}

	private OrganisationViews.Organisation organisation(java.sql.ResultSet rs) throws java.sql.SQLException {
		var id = (UUID) rs.getObject("id");
		var version = rs.getInt("logo_version");
		return new OrganisationViews.Organisation(id, rs.getString("name"), rs.getString("slug"), rs.getString("country"),
			rs.getString("primary_colour"), rs.getBoolean("corporate_enabled"), rs.getString("role"),
			version == 0 ? null : "/organisations/%s/logo?v=%d".formatted(id, version), rs.getTimestamp("created_at").toInstant());
	}

	private static OffsetDateTime db(Instant value) { return value.atOffset(ZoneOffset.UTC); }

	private static String required(String value, int max, String message) {
		var clean = value == null ? "" : value.strip();
		if (clean.isEmpty() || clean.length() > max) {
			throw BusinessException.invalid(message);
		}
		return clean;
	}

	private static String hash(String token) {
		try {
			return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private record InviteRow(UUID id, UUID organisationId, String role) {
	}
}
