package com.playchale.api.competitions.internal.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.playchale.api.shared.error.BusinessException;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * A team in a league, with its squad for that league. The team itself (name, captain, members)
 * belongs to the teams module; the squad is who plays for it here, one team per player per league
 * (the database holds that). A squad can be empty: a schools' league may not list players.
 */
@Entity
@Table(name = "competition_entries")
public class Entry {

	/** Waiting for the team's captain to accept the organiser's invitation. */
	public static final String INVITED = "invited";

	public static final String ENTERED = "entered";

	@EmbeddedId
	private EntryId id;

	private String status;

	private Instant enteredAt;

	@ElementCollection
	@CollectionTable(name = "entry_players",
			joinColumns = { @JoinColumn(name = "competition_id"), @JoinColumn(name = "team_id") })
	@OrderBy("addedAt")
	private List<SquadPlayer> players = new ArrayList<>();

	protected Entry() {
	}

	public Entry(UUID competitionId, UUID teamId, boolean invited, Instant now) {
		this.id = new EntryId(competitionId, teamId);
		this.status = invited ? INVITED : ENTERED;
		this.enteredAt = now;
	}

	/** The team's captain said yes. */
	public void accept(Instant now) {
		this.status = ENTERED;
		this.enteredAt = now;
	}

	public boolean isInvited() {
		return INVITED.equals(status);
	}

	public void add(UUID userId, Instant now) {
		if (!has(userId)) {
			players.add(new SquadPlayer(userId, now));
		}
	}

	/** Out of the squad for this league. The captain stays in: they hand the armband over first. */
	public void remove(UUID userId, UUID captainId) {
		if (captainId.equals(userId)) {
			throw BusinessException.conflict("The captain can’t be dropped. Hand the armband over first.");
		}
		players.removeIf(p -> p.userId().equals(userId));
	}

	public boolean has(UUID userId) {
		return players.stream().anyMatch(p -> p.userId().equals(userId));
	}

	public List<UUID> playerIds() {
		return players.stream().map(SquadPlayer::userId).toList();
	}

	public UUID getCompetitionId() {
		return id.competitionId();
	}

	public UUID getTeamId() {
		return id.teamId();
	}

	public String getStatus() {
		return status;
	}

	public Instant getEnteredAt() {
		return enteredAt;
	}

}
