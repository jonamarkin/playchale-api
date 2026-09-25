package com.playchale.api.users;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Reading and writing players. Spring Data writes the queries from the method names. */
public interface UserRepository extends JpaRepository<User, UUID> {

	Optional<User> findByPhone(String phone);

}
