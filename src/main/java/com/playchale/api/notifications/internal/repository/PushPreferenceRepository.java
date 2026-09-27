package com.playchale.api.notifications.internal.repository;

import java.util.UUID;

import com.playchale.api.notifications.internal.domain.PushPreference;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PushPreferenceRepository extends JpaRepository<PushPreference, UUID> {

}
