package com.playchale.api.notifications.internal.service;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.playchale.api.notifications.internal.domain.Notification;
import com.playchale.api.notifications.internal.repository.NotificationRepository;
import com.playchale.api.users.api.UserDirectory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A player's notifications. They're written by the listeners in this package as things happen. */
@Service
public class NotificationService {

	/** The list shows the latest this many. */
	private static final int LIMIT = 50;

	private final NotificationRepository notifications;

	private final UserDirectory users;

	private final Clock clock;

	NotificationService(NotificationRepository notifications, UserDirectory users, Clock clock) {
		this.notifications = notifications;
		this.users = users;
		this.clock = clock;
	}

	/** notifications.list: newest first. */
	@Transactional(readOnly = true)
	public List<NotificationResponse> list(UUID me) {
		var found = notifications.findByUserIdOrderByCreatedAtDesc(me, Limit.of(LIMIT));
		var actors = users.findAll(found.stream().map(Notification::getActorId).filter(Objects::nonNull).distinct().toList());
		return found.stream().map(n -> new NotificationResponse(n.getId(), n.getUserId(), n.getKind(), n.getTitle(), n.getBody(),
				n.getLink(), n.getActorId(), n.getCreatedAt(), n.isRead(),
				n.getActorId() == null || !actors.containsKey(n.getActorId()) ? null : actors.get(n.getActorId()).toPublic()))
			.toList();
	}

	/** notifications.markAllRead */
	@Transactional
	public void markAllRead(UUID me) {
		notifications.markAllRead(me, clock.instant());
	}

	void send(UUID to, String kind, String title, String body, String link, UUID actor) {
		notifications.save(new Notification(to, kind, title, body, link, actor, clock.instant()));
	}

}
