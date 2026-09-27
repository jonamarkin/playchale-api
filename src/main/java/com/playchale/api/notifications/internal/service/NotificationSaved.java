package com.playchale.api.notifications.internal.service;

import java.util.UUID;

/** A notification was written. Once that's committed, it goes to the player's phones too. */
record NotificationSaved(UUID id, UUID userId, String kind, String title, String body, String link) {
}
