package com.playchale.api.teams.internal.domain;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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

	private Instant createdAt;

	@ElementCollection
	@CollectionTable(name = "team_members", joinColumns = @JoinColumn(name = "team_id"))
	@OrderBy("joinedAt")
	private List<TeamMember> members = new ArrayList<>();

	protected Team() {
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
