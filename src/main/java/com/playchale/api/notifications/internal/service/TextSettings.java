package com.playchale.api.notifications.internal.service;

import java.util.UUID;

import com.playchale.api.integration.sms.SmsSender;
import com.playchale.api.users.api.UserDirectory;
import com.playchale.api.users.api.UserSummary;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Texts to a member's own number. For now that's one thing, the reminder the evening before a game
 * they're in ({@link GameReminders}): on unless they turn it off. Texts go to Ghanaian numbers only,
 * the ones the SMS provider reaches.
 */
@Service
public class TextSettings {

	/** The only numbers texts reach. */
	static final String TEXTED = "+233";

	private final JdbcClient jdbc;

	private final ObjectProvider<SmsSender> sms;

	private final UserDirectory users;

	TextSettings(JdbcClient jdbc, ObjectProvider<SmsSender> sms, UserDirectory users) {
		this.jdbc = jdbc;
		this.sms = sms;
		this.users = users;
	}

	/**
	 * @param reminders whether they're texted the evening before their games
	 * @param phone     the number texts go to (E.164), or null when they have none texts reach
	 */
	public record Settings(boolean reminders, String phone) {
	}

	@Transactional(readOnly = true)
	public Settings settings(UUID me) {
		var phone = users.find(me).map(UserSummary::phone).filter(TextSettings::texted).orElse(null);
		return new Settings(remindsByText(me), sms.getIfAvailable() == null ? null : phone);
	}

	@Transactional
	public Settings setReminders(UUID me, boolean on) {
		jdbc.sql("""
				INSERT INTO text_preferences (user_id, reminders) VALUES (:id, :on)
				ON CONFLICT (user_id) DO UPDATE SET reminders = EXCLUDED.reminders
				""").param("id", me).param("on", on).update();
		return settings(me);
	}

	/** Whether they're texted the evening before their games: yes unless they said no. */
	boolean remindsByText(UUID userId) {
		return jdbc.sql("SELECT reminders FROM text_preferences WHERE user_id = :id").param("id", userId).query(Boolean.class).optional().orElse(true);
	}

	/** A number texts reach. */
	static boolean texted(String phone) {
		return phone != null && phone.startsWith(TEXTED);
	}

}
