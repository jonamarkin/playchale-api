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

	@Override
	@Transactional
	public UserSummary registerOrFindByEmail(String email, String country) {
		var user = users.findBySignInEmail(email)
			.orElseGet(() -> users.save(User.signedUpByEmail(email, country, User.TINTS.get(random.nextInt(User.TINTS.size())))));
		return summary(user);
	}

	@Override
	@Transactional
	public UserSummary registerOrFindByGoogle(String googleSub, String email, String country) {
		var user = users.findByGoogleSub(googleSub).orElseGet(() -> {
			var found = users.findBySignInEmail(email)
				.orElseGet(() -> User.signedUpByEmail(email, country, User.TINTS.get(random.nextInt(User.TINTS.size()))));
			found.signInByGoogle(googleSub);
			return users.save(found);
		});
		return summary(user);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UUID> signsInWith(String method, String address) {
		return ("phone".equals(method) ? users.findByPhone(address) : users.findBySignInEmail(address)).map(User::getId);
	}

	@Override
	@Transactional
	public UserSummary addSignInMethod(UUID userId, String method, String address) {
		var user = users.findById(userId).orElseThrow();
		if ("phone".equals(method)) {
			user.signInByPhone(address);
		}
		else {
			user.signInByEmail(address);
		}
		return summary(users.saveAndFlush(user));
	}

	@Override
	@Transactional
	public UserSummary removeSignInMethod(UUID userId, String method) {
		var user = users.findById(userId).orElseThrow();
		user.stopSigningInBy(method);
		return summary(users.save(user));
	}

	/** A player signed up by email has no phone: the web app's User type still wants a string there. */
	static UserSummary summary(User u) {
		return new UserSummary(u.getId(), u.getPhone() == null ? "" : u.getPhone(), u.getName(), u.getHandle(), u.getAvatarUrl(), u.getAvatarSeed(), u.getTint(),
				u.getArea(), u.getSports(), u.getRoles(), u.getCreatedAt(), u.isOnboarded(), u.getPayoutPhone(), u.getEmail(), u.getSignInEmail(), u.getCountry(),
				u.getTermsVersion(), u.signsInWithGoogle());
	}

}
