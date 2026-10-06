package com.playchale.api.admin.internal.service;

import java.util.Optional;
import java.util.UUID;

import com.playchale.api.shared.error.BusinessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who works for PlayChale, and the gate every admin endpoint goes through.
 *
 * <p>The check is here and not in the web layer on purpose: a route that forgets a guard is the
 * usual way an admin screen leaks, so the service refuses rather than trusting its caller. There is
 * no endpoint that grants staff: the first row is an INSERT run by hand on the server, and only an
 * owner can add anyone after that.
 *
 * <p>An anonymised account (V11 keeps the row, emptied) is not staff whatever the table says.
 */
@Service
public class Staff {

	/** Can look things up and answer for people. */
	public static final String SUPPORT = "support";

	/** The same, and can add or remove staff. */
	public static final String OWNER = "owner";

	private final JdbcClient jdbc;

	Staff(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public Optional<String> roleOf(UUID userId) {
		if (userId == null) {
			return Optional.empty();
		}
		return jdbc.sql("""
				SELECT s.role FROM platform_staff s
				JOIN users u ON u.id = s.user_id
				WHERE s.user_id = :id AND u.deleted_at IS NULL
				""").param("id", userId).query(String.class).optional();
	}

	public boolean isStaff(UUID userId) {
		return roleOf(userId).isPresent();
	}

	/**
	 * Refuses unless this person works here. Says "not found" rather than "forbidden": an admin area
	 * should not confirm its own existence to someone poking at it.
	 */
	public String require(UUID userId) {
		return roleOf(userId).orElseThrow(() -> BusinessException.notFound("Not found."));
	}

	/** The same, and only an owner passes. */
	public void requireOwner(UUID userId) {
		if (!OWNER.equals(require(userId))) {
			throw BusinessException.conflict("Only an owner can change who works here.");
		}
	}

	@Transactional
	public void add(UUID actorId, UUID userId, String role, String note) {
		requireOwner(actorId);
		if (!SUPPORT.equals(role) && !OWNER.equals(role)) {
			throw BusinessException.invalid("A staff role is 'support' or 'owner'.");
		}
		jdbc.sql("""
				INSERT INTO platform_staff (user_id, role, added_by, note) VALUES (:id, :role, :by, :note)
				ON CONFLICT (user_id) DO UPDATE SET role = :role, added_by = :by, note = :note
				""").param("id", userId).param("role", role).param("by", actorId).param("note", note).update();
	}

	@Transactional
	public void remove(UUID actorId, UUID userId) {
		requireOwner(actorId);
		if (actorId.equals(userId)) {
			// Removing the last owner would lock everyone out of a thing only SQL can reopen.
			throw BusinessException.conflict("You can’t remove yourself. Ask another owner.");
		}
		jdbc.sql("DELETE FROM platform_staff WHERE user_id = :id").param("id", userId).update();
	}

}
