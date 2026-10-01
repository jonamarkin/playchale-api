package com.playchale.api.notifications.internal.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.playchale.api.notifications.internal.domain.EmailPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EmailPreferenceRepository extends JpaRepository<EmailPreference, UUID> {

	Optional<EmailPreference> findByToken(UUID token);

	/** Just the IDs: an audience is counted in thousands, and nothing here needs the rows. */
	@Query("SELECT p.userId FROM EmailPreference p WHERE p.marketing = true")
	List<UUID> marketingUserIds();

	@Query("SELECT p.userId FROM EmailPreference p WHERE p.marketing = true AND p.digest = 'weekly'")
	List<UUID> digestUserIds();

}
