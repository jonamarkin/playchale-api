package com.playchale.api.users.internal.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.playchale.api.shared.error.BusinessException;
import com.playchale.api.users.api.GuestAccountMerged;
import com.playchale.api.users.api.GuestHostBecameMember;
import com.playchale.api.users.api.Terms;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.users.api.UserSummary;
import com.playchale.api.users.internal.domain.User;
import com.playchale.api.users.internal.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UserDirectoryService implements UserDirectory {

	private static final SecureRandom random = new SecureRandom();

	private final UserRepository users;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	UserDirectoryService(UserRepository users, ApplicationEventPublisher events, Clock clock) {
		this.users = users;
		this.events = events;
		this.clock = clock;
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
		// Someone who hosted as a guest under this number is that account: it's theirs now.
		var user = users.findByPhone(phone)
			.or(() -> users.findLiveGuestByPhone(phone).map(guest -> becomeMember(guest, "phone", phone, null)))
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
		return switch (method) {
			case "phone" -> users.findByPhone(address).map(User::getId);
			case "google" -> users.findByGoogleSub(address).map(User::getId);
			default -> users.findBySignInEmail(address).map(User::getId);
		};
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
		var saved = summary(users.saveAndFlush(user));
		// They hosted as a guest under this number: those games are theirs.
		if ("phone".equals(method)) {
			users.findLiveGuestByPhone(address).filter(guest -> !guest.getId().equals(userId)).ifPresent(guest -> mergeGuest(guest.getId(), userId));
		}
		return saved;
	}

	@Override
	@Transactional
	public UserSummary registerGuestHost(String name, String phone, String country, String termsVersion) {
		if (termsVersion == null || !Terms.CURRENT.equals(termsVersion)) {
			throw BusinessException.invalid(termsVersion == null || termsVersion.isBlank() ? "Agree to the Terms and Privacy Policy to continue."
					: "The Terms have changed since this page loaded. Reload it and try again.");
		}
		if (users.findByPhone(phone).isPresent()) {
			throw BusinessException.conflict("That number has a PlayChale account. Sign in with it instead.");
		}
		if (users.findLiveGuestByPhone(phone).isPresent()) {
			throw BusinessException.conflict("That number is already hosting without an account on another phone. Use the manage link from there, or sign up with the number.");
		}
		var user = User.guestHost(name == null ? "" : name, phone, country, User.TINTS.get(random.nextInt(User.TINTS.size())), termsVersion, clock.instant());
		try {
			return summary(users.saveAndFlush(user));
		}
		catch (DataIntegrityViolationException twice) {
			throw BusinessException.conflict("That number is already hosting without an account on another phone. Use the manage link from there, or sign up with the number.");
		}
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isGuest(UUID id) {
		return id != null && users.findById(id).map(User::isGuest).orElse(false);
	}

	@Override
	@Transactional
	public UserSummary upgradeGuest(UUID guestId, String method, String address, String email) {
		var guest = users.findById(guestId).filter(User::isGuest).orElseThrow(() -> BusinessException.notFound("That guest account isn’t there any more."));
		return summary(becomeMember(guest, method, address, email));
	}

	@Override
	@Transactional
	public void mergeGuest(UUID guestId, UUID into) {
		var guest = users.findById(guestId).filter(User::isGuest).orElse(null);
		if (guest == null || guestId.equals(into)) {
			return;
		}
		events.publishEvent(new GuestAccountMerged(guestId, into));
		guest.delete(clock.instant());
		users.save(guest);
	}

	/** The guest account, signing in with this from now on: no longer a guest, and its games still its own. */
	private User becomeMember(User guest, String method, String address, String email) {
		switch (method) {
			case "phone" -> guest.signInByPhone(address);
			case "google" -> {
				guest.signInByGoogle(address);
				if (email != null && users.findBySignInEmail(email).isEmpty()) {
					guest.signInByEmail(email);
				}
			}
			default -> guest.signInByEmail(address);
		}
		guest.stopBeingGuest();
		var saved = users.save(guest);
		events.publishEvent(new GuestHostBecameMember(saved.getId()));
		return saved;
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
				u.getArea(), u.getAreas(), u.getSports(), u.getRoles(), u.getCreatedAt(), u.isOnboarded(), u.getPayoutPhone(), u.getEmail(), u.getSignInEmail(), u.getCountry(),
				u.getTermsVersion(), u.signsInWithGoogle(), u.isGuest() ? Boolean.TRUE : null, u.getGuestPhone());
	}

}
