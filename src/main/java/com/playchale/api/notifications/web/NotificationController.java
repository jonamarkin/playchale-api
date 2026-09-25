package com.playchale.api.notifications.web;

import java.util.List;

import com.playchale.api.notifications.internal.service.NotificationResponse;
import com.playchale.api.notifications.internal.service.NotificationService;
import com.playchale.api.shared.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class NotificationController {

	private final NotificationService notifications;

	NotificationController(NotificationService notifications) {
		this.notifications = notifications;
	}

	/** notifications.list */
	@GetMapping("/notifications")
	List<NotificationResponse> list(CurrentUser me) {
		return notifications.list(me.id());
	}

	/** notifications.markAllRead */
	@PostMapping("/notifications/read-all")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void markAllRead(CurrentUser me) {
		notifications.markAllRead(me.id());
	}

}
