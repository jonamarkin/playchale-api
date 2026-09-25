package com.playchale.api.games.internal.repository;

import java.util.UUID;

import com.playchale.api.games.internal.domain.GameResult;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameResultRepository extends JpaRepository<GameResult, UUID> {
}
