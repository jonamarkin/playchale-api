package com.playchale.api.profiles.internal.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.users.api.UserSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Player profiles: who they are, plus their record. Records come from verified game results, which
 * arrive with the results module; until then every player's record is empty.
 */
@Service
public class PlayerProfileService {

	private final UserDirectory users;

	PlayerProfileService(UserDirectory users) {
		this.users = users;
	}

	/** profiles.get. Phone numbers are only included when the viewer is the player. */
	@Transactional(readOnly = true)
	public PlayerProfile get(UUID userId, Optional<UUID> viewer) {
		return users.find(userId)
			.map(user -> profileOf(user, viewer))
			.orElseThrow(() -> BusinessException.notFound("That player could not be found."));
	}

	/** profiles.getByHandle */
	@Transactional(readOnly = true)
	public Optional<PlayerProfile> getByHandle(String handle, Optional<UUID> viewer) {
		return users.findByHandle(handle).map(user -> profileOf(user, viewer));
	}

	/** profiles.history: verified results they played in, newest first. */
	@Transactional(readOnly = true)
	public List<MatchRecord> history(UUID userId) {
		return List.of();
	}

	private PlayerProfile profileOf(UserSummary user, Optional<UUID> viewer) {
		var shown = viewer.map(user::as).orElseGet(user::toPublic);
		var bySport = user.sports().stream().map(sport -> new SportRecord(sport, PlayerStats.NONE, List.of())).toList();
		return new PlayerProfile(shown, PlayerStats.NONE, List.of(), bySport, List.of());
	}

}
