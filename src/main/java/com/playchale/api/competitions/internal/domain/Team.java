package com.playchale.api.competitions.internal.domain;

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
 * A squad in a competition. Players keep their own profiles and stats; the team is who they play
 * for. The captain runs it, and the organiser can step in.
 */
@Entity
@Table(name = "teams")
public class Team {

	private static final SecureRandom random = new SecureRandom();

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	private UUID competitionId;

	private String name;

	private UUID captainId;

	private String tint;

	/** Goes in the squad link. Only the captain and the organiser ever see it. */
	private String joinToken;

	private Instant createdAt;

	@ElementCollection
	@CollectionTable(name = "team_players", joinColumns = @JoinColumn(name = "team_id"))
	@OrderBy("addedAt")
	private List<SquadMember> players = new ArrayList<>();

	protected Team() {
	}

	/**
	 * A new team.
	 *
	 * @param captainPlays whether the captain is in the squad. An organiser who sets a team up without
	 *                     naming a captain runs it without playing for it.
	 */
	public Team(UUID competitionId, String name, UUID captainId, boolean captainPlays, String tint, Instant now) {
		var trimmed = name == null ? "" : name.strip();
		if (trimmed.isEmpty() || trimmed.length() > 60) {
			throw BusinessException.invalid("Give the team a name.");
		}
		this.competitionId = competitionId;
		this.name = trimmed;
		this.captainId = captainId;
		this.tint = tint;
		this.createdAt = now;
		this.joinToken = newToken();
		if (captainPlays) {
			add(captainId, now);
		}
	}

	public void add(UUID userId, Instant now) {
		if (!has(userId)) {
			players.add(new SquadMember(userId, competitionId, now));
		}
	}

	public void remove(UUID userId) {
		if (captainId.equals(userId)) {
			throw BusinessException.conflict("The captain can’t be dropped. Hand the armband over first.");
		}
		players.removeIf(p -> p.userId().equals(userId));
	}

	public boolean has(UUID userId) {
		return players.stream().anyMatch(p -> p.userId().equals(userId));
	}

	/** Captains run their own squads; the organiser can step in on any of them. */
	public boolean isRunBy(UUID userId, Competition competition) {
		return captainId.equals(userId) || competition.isOrganisedBy(userId);
	}

	public List<UUID> playerIds() {
		return players.stream().map(SquadMember::userId).toList();
	}

	private static String newToken() {
		var bytes = new byte[12];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	public UUID getId() {
		return id;
	}

	public UUID getCompetitionId() {
		return competitionId;
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
