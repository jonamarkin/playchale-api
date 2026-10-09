package com.playchale.api.events.internal.service;

import com.playchale.api.users.api.AccountDeleted;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Events and a player deleting their account. The results they were part of belong to the event as
 * much as to them, so their places stay, unlinked. A name they joined under goes with them; a name an
 * admin typed is the organisers' own record and stays.
 */
@Component
class EventAccountDeletion {

	private final JdbcClient jdbc;

	EventAccountDeletion(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@EventListener
	void on(AccountDeleted e) {
		jdbc.sql("""
				UPDATE event_entries en SET name = 'Former participant' FROM event_entry_people ep, event_people p, event_games g
				WHERE ep.entry_id = en.id AND p.id = ep.person_id AND g.id = en.game_id
				  AND p.user_id = :user AND p.source = 'link' AND g.entry_kind = 'single'
				""").param("user", e.userId()).update();
		jdbc.sql("UPDATE event_people SET display_name = 'Former participant' WHERE user_id = :user AND source = 'link'")
			.param("user", e.userId()).update();
		jdbc.sql("UPDATE event_people SET user_id = NULL WHERE user_id = :user").param("user", e.userId()).update();
		jdbc.sql("DELETE FROM event_game_coordinators WHERE user_id = :user").param("user", e.userId()).update();
	}

}
