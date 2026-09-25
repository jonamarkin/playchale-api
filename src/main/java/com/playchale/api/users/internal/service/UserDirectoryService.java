package com.playchale.api.users.internal.service;

import java.security.SecureRandom;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.users.api.UserSummary;
import com.playchale.api.users.internal.domain.User;
import com.playchale.api.users.internal.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UserDirectoryService implements UserDirectory {

	private static final SecureRandom random = new SecureRandom();

	private final UserRepository users;

	UserDirectoryService(UserRepository users) {
		this.users = users;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UserSummary> find(UUID id) {
		return users.findById(id).map(UserDirectoryService::summary);
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, UserSummary> findAll(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		return users.findAllById(ids).stream().collect(Collectors.toMap(User::getId, UserDirectoryService::summary));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UserSummary> findByHandle(String handle) {
		if (handle == null || handle.isBlank()) {
			return Optional.empty();
		}
		return users.findByHandleIgnoreCase(handle.trim()).map(UserDirectoryService::summary);
	}

	@Override
	@Transactional
	public UserSummary registerOrFind(String phone, String country) {
		var user = users.findByPhone(phone)
			.orElseGet(() -> users.save(new User(phone, country, User.TINTS.get(random.nextInt(User.TINTS.size())))));
		return summary(user);
	}

	static UserSummary summary(User u) {
		return new UserSummary(u.getId(), u.getPhone(), u.getName(), u.getHandle(), u.getAvatarUrl(), u.getTint(),
				u.getArea(), u.getSports(), u.getPosition(), u.getCreatedAt(), u.isOnboarded(), u.getPayoutPhone());
	}

}
