package com.playchale.api.users.internal.repository;

import java.util.Optional;
import java.util.UUID;

import com.playchale.api.users.internal.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

	Optional<User> findByPhone(String phone);

	/** Handles are unique ignoring case (see the users_handle_unique index). */
	Optional<User> findByHandleIgnoreCase(String handle);

}
