package com.playchale.api.users.internal.domain;

import jakarta.persistence.Embeddable;

/**
 * One position (or athletics event) a player picked for one of their sports: a row of player_roles.
 *
 * @param sport a sport id from the catalogue, e.g. "football"
 * @param role  a role id from that sport's list, e.g. "goalkeeper"
 * @param rank  0 for their main one in that sport, then 1...
 */
@Embeddable
public record PlayerRole(String sport, String role, int rank) {
}
