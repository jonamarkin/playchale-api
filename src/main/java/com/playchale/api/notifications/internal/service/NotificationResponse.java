package com.playchale.api.notifications.internal.service;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.playchale.api.users.api.UserSummary;

/** A notification as the web app's NotificationView: with the person who caused it filled in. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationResponse(UUID id, UUID userId, String kind, String title, String body, String link, UUID actorId,
		Instant createdAt, boolean read, UserSummary actor) {
}
