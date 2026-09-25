package com.playchale.api.profiles.internal.service;

import java.util.List;

/** One sport's record for a player; {@code form} is the latest results, oldest first, at most five. */
public record SportRecord(String sport, PlayerStats stats, List<String> form) {
}
