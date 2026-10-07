package com.playchale.api.teams.internal.domain;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.playchale.api.shared.error.BusinessException;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

/**
 * A team: people who play together, run by a captain. It stands on its own and can be entered into
 * leagues, invited to games and play friendlies. Players keep their own profiles and stats; the
 * team is who they play for.
 */
@Entity
@Table(name = "teams")
public class Team {

	/** Crest backgrounds, so teams are told apart at a glance. */
	public static final List<String> TINTS = List.of("#7cf0c8", "#a9c4f2", "#f2d4a9", "#d9b8e8", "#b7d3c9", "#f5c9b3", "#c9a1d8", "#e8e8e4");

	private static final SecureRandom random = new SecureRandom();

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	private String name;

	private UUID captainId;

	private String tint;

	/** Goes in the join link. Only the captain ever sees it (and a league organiser running its squad). */
	private String joinToken;

	/*
	 * The crest's bytes (logo, logo_type) live in this table but not in this entity: a team is
	 * loaded for every page that names it (a league, a fixture on Discover), and a hundred kilobytes
	 * of image each time would make all of those slow and cost database traffic. TeamService reads
	 * and writes them directly, only when a crest is served or changed.
	 */

	/** When the crest last changed, which the web app puts in its URL so a new one isn't cached over. */
	private Instant logoVersion;

	private Instant createdAt;

	@ElementCollection
	@CollectionTable(name = "team_members", joinColumns = @JoinColumn(name = "team_id"))
	@OrderBy("joinedAt")
	private List<TeamMember> members = new ArrayList<>();

	protected Team() {
	}

	/** Image types a crest may be, and what each one's first bytes look like. */
	private static final Map<String, byte[]> LOGO_TYPES = Map.of(
			"image/webp", new byte[] { 'R', 'I', 'F', 'F' },
			"image/png", new byte[] { (byte) 0x89, 'P', 'N', 'G' },
			"image/jpeg", new byte[] { (byte) 0xff, (byte) 0xd8, (byte) 0xff });

	/** A crest is resized in the browser first, so anything this big is a mistake or an attack. */
	public static final int MAX_LOGO_BYTES = 128 * 1024;

	/**
	 * Puts a crest on the team. The bytes have to be an image of the type they claim, so a file
	 * renamed to .png can't be served back to someone's browser as one. Returns the type to store
	 * them as; storing them is the caller's (see the note on the fields).
	 */
	public String wearCrest(byte[] image, String contentType, Instant now) {
		var type = contentType == null ? "" : contentType.split(";")[0].strip().toLowerCase(Locale.ROOT);
		var magic = LOGO_TYPES.get(type);
		if (magic == null) {
			throw BusinessException.invalid("A crest has to be a PNG, JPEG or WebP image.");
		}
		if (image == null || image.length == 0) {
			throw BusinessException.invalid("That file is empty.");
		}
		if (image.length > MAX_LOGO_BYTES) {
			throw BusinessException.invalid("That image is too big. Pick one under %d KB.".formatted(MAX_LOGO_BYTES / 1024));
		}
		if (image.length < magic.length || !Arrays.equals(Arrays.copyOf(image, magic.length), magic)) {
			throw BusinessException.invalid("That file isn’t the image it claims to be.");
		}
		this.logoVersion = now;
		return type;
	}

	/** Back to the plain coloured crest. */
	public void dropCrest() {
		this.logoVersion = null;
	}

	public Instant getLogoVersion() {
		return logoVersion;
	}

	/**
	 * A new team.
	 *
	 * @param captainPlays whether the captain is a member. An organiser who sets up a school's team
	 *                     runs it without playing for it.
	 */
	public Team(String name, UUID captainId, boolean captainPlays, String tint, Instant now) {
		this.name = cleanName(name);
		this.captainId = captainId;
		this.tint = tint != null && TINTS.contains(tint) ? tint : TINTS.getFirst();
		this.createdAt = now;
		this.joinToken = newToken();
		if (captainPlays) {
			add(captainId, now);
		}
	}

	public void rename(String name) {
		this.name = cleanName(name);
	}

	public void recolour(String tint) {
		if (tint == null || !TINTS.contains(tint)) {
			throw BusinessException.invalid("Pick one of the team colours.");
		}
		this.tint = tint;
	}

	/** The captain hands the armband to someone in the team. */
	public void handOver(UUID to) {
		if (!has(to)) {
			throw BusinessException.invalid("Pick someone in the team to be captain.");
		}
		this.captainId = to;
	}

	/** Adds someone who isn't in yet. Returns whether they were added. */
	public boolean add(UUID userId, Instant now) {
		if (has(userId)) {
			return false;
		}
		members.add(new TeamMember(userId, now));
		return true;
	}

	/** Takes someone out. The captain has to hand the armband over first. */
	public void remove(UUID userId) {
		if (captainId.equals(userId)) {
			throw BusinessException.conflict("The captain can’t leave. Hand the armband to someone else first.");
		}
		members.removeIf(m -> m.userId().equals(userId));
	}

	/** An account being deleted: out of the team, whatever their role. */
	public void forget(UUID userId) {
		members.removeIf(m -> m.userId().equals(userId));
	}

	public boolean has(UUID userId) {
		return members.stream().anyMatch(m -> m.userId().equals(userId));
	}

	public boolean isCaptain(UUID userId) {
		return captainId.equals(userId);
	}

	public List<UUID> memberIds() {
		return members.stream().map(TeamMember::userId).toList();
	}

	private static String cleanName(String name) {
		var trimmed = name == null ? "" : name.strip();
		if (trimmed.isEmpty() || trimmed.length() > 60) {
			throw BusinessException.invalid("Give the team a name.");
		}
		return trimmed;
	}

	private static String newToken() {
		var bytes = new byte[12];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	public UUID getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public UUID getCaptainId() {
		return captainId;
	}

	public String getTint() {
		return tint;
	}

	public String getJoinToken() {
		return joinToken;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
