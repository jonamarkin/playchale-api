package com.playchale.api.games.internal.domain;

import jakarta.persistence.Embeddable;

/** One set's score, home side first: points in volleyball, games in tennis. */
@Embeddable
public record SetScore(int home, int away) {
}
