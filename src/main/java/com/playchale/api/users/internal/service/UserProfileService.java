package com.playchale.api.users.internal.service;

import java.util.Optional;
import java.util.UUID;

import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.users.api.UserSummary;
import com.playchale.api.users.internal.domain.User;
import com.playchale.api.users.internal.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A player editing their own profile, and finishing onboarding. */
@Service
public class UserProfileService {

	private static final String HANDLE_TAKEN = "That username is taken. Try another.";

	private final UserRepository users;

	UserProfileService(UserRepository users) {
		this.users = users;
	}

	/** profiles.update */
	@Transactional
	public UserSummary update(UUID userId, ProfileChanges changes) {
		var user = load(userId);
		apply(user, changes);
		return save(user);
	}

	/** profiles.completeOnboarding: the same changes, and then they're in. */
	@Transactional
	public UserSummary completeOnboarding(UUID userId, ProfileChanges changes) {
		var user = load(userId);
		apply(user, changes);
		user.finishOnboarding();
		return save(user);
	}

	/**
	 * profiles.isHandleAvailable: free for this player to take. Their own handle counts as free, so
	 * saving a profile without changing it isn't refused.
	 */
	@Transactional(readOnly = true)
	public boolean isHandleAvailable(String handle, Optional<UUID> viewer) {
		if (!User.isValidHandle(handle)) {
			return false;
		}
		return users.findByHandleIgnoreCase(User.normaliseHandle(handle))
			.map(owner -> viewer.isPresent() && owner.getId().equals(viewer.get()))
			.orElse(true);
	}

	/**
	 * The handle is checked first, before anything on the player changes: Hibernate writes pending
	 * changes before running a query, and the check is a query.
	 */
	private void apply(User user, ProfileChanges changes) {
		if (changes.handle() != null) {
			var handle = User.normaliseHandle(changes.handle());
			var taken = User.isValidHandle(handle)
					&& users.findByHandleIgnoreCase(handle).filter(owner -> !owner.getId().equals(user.getId())).isPresent();
			if (taken) {
				throw BusinessException.conflict(HANDLE_TAKEN);
			}
			user.changeHandle(handle);
		}
		if (changes.name() != null) {
			user.rename(changes.name());
		}
		if (changes.area() != null) {
			user.moveTo(changes.area());
		}
		if (changes.sports() != null) {
			user.playSports(changes.sports());
		}
		if (changes.position() != null) {
			user.playPosition(changes.position());
		}
		if (changes.payoutPhone() != null) {
			user.payTo(changes.payoutPhone());
		}
	}

	/**
	 * Writes now rather than at commit, so a handle someone else claimed a moment ago (the unique
	 * index catches it) becomes a clear "taken" instead of a failed request.
	 */
	private UserSummary save(User user) {
		try {
			return UserDirectoryService.summary(users.saveAndFlush(user));
		}
		catch (DataIntegrityViolationException e) {
			throw BusinessException.conflict(HANDLE_TAKEN);
		}
	}

	private User load(UUID userId) {
		return users.findById(userId).orElseThrow(() -> BusinessException.unauthenticated("Please sign in to continue."));
	}

}
