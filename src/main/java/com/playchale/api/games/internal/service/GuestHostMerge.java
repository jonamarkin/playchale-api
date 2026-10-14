package com.playchale.api.games.internal.service;

import java.util.UUID;

import com.playchale.api.users.api.GuestAccountMerged;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A guest host signed in to the account they already had: the games they hosted are that
 * account's now, with everything around them (their own spot, results, messages, repeats). Where the
 * account was already in one of the games, its own spot stays (paid if either was). In the
 * sign-in's transaction, before the guest account is retired.
 */
@Component
class GuestHostMerge {

	private final JdbcClient jdbc;

	GuestHostMerge(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@EventListener
	void on(GuestAccountMerged e) {
		var guest = e.guestId();
		var into = e.into();
		update("UPDATE games SET host_id = :into WHERE host_id = :guest", guest, into);
		update("UPDATE game_series SET host_id = :into WHERE host_id = :guest", guest, into);

		// Their own spots: one per game, the account's where it had one, paid if either was.
		update("""
				UPDATE game_participants mine SET paid = true FROM game_participants theirs
				WHERE theirs.user_id = :guest AND theirs.paid AND mine.user_id = :into AND mine.game_id = theirs.game_id
				""", guest, into);
		update("""
				DELETE FROM game_participants theirs WHERE theirs.user_id = :guest
				  AND EXISTS (SELECT 1 FROM game_participants mine WHERE mine.game_id = theirs.game_id AND mine.user_id = :into)
				""", guest, into);
		update("UPDATE game_participants SET user_id = :into WHERE user_id = :guest", guest, into);
		update("UPDATE game_participants SET guest_added_by = :into WHERE guest_added_by = :guest", guest, into);

		// Their result lines, keyed by the player.
		update("""
				DELETE FROM result_players theirs WHERE theirs.user_id = :guest
				  AND EXISTS (SELECT 1 FROM result_players mine WHERE mine.game_id = theirs.game_id AND mine.user_id = :into)
				""", guest, into);
		jdbc.sql("UPDATE result_players SET user_id = :into, player_key = :key WHERE user_id = :guest").param("into", into)
			.param("key", into.toString()).param("guest", guest).update();
		update("UPDATE game_results SET recorded_by = :into WHERE recorded_by = :guest", guest, into);
		for (var table : new String[] { "result_confirmations", "result_disputes", "game_series_optouts", "game_invites" }) {
			var key = table.equals("game_series_optouts") ? "series_id" : "game_id";
			update("DELETE FROM %s theirs WHERE theirs.user_id = :guest AND EXISTS (SELECT 1 FROM %s mine WHERE mine.%s = theirs.%s AND mine.user_id = :into)"
				.formatted(table, table, key, key), guest, into);
			update("UPDATE %s SET user_id = :into WHERE user_id = :guest".formatted(table), guest, into);
		}
		update("UPDATE game_invites SET invited_by = :into WHERE invited_by = :guest", guest, into);
		update("UPDATE game_messages SET user_id = :into WHERE user_id = :guest", guest, into);
		update("UPDATE game_departures SET user_id = :into WHERE user_id = :guest", guest, into);
	}

	private void update(String sql, UUID guest, UUID into) {
		jdbc.sql(sql).param("guest", guest).param("into", into).update();
	}

}
