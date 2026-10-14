package com.playchale.api.users.internal.repository;

import java.util.Optional;
import java.util.UUID;

import com.playchale.api.users.internal.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

	Optional<User> findByPhone(String phone);

	/** Stored lower-case, so an exact match is enough. */
	Optional<User> findBySignInEmail(String email);

	Optional<User> findByGoogleSub(String googleSub);

	/** The guest host still waiting for whoever holds this number to sign up. */
	@Query("select u from User u where u.guest = true and u.guestPhone = :phone and u.deletedAt is null")
	Optional<User> findLiveGuestByPhone(@Param("phone") String phone);

	/** Handles are unique ignoring case (see the users_handle_unique index). */
	Optional<User> findByHandleIgnoreCase(String handle);

}
