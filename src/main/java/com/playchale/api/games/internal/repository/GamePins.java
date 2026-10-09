package com.playchale.api.games.internal.repository;

import com.playchale.api.shared.maps.PinTable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Pins of games somewhere that isn't a partner venue, for the daily look-up of place coordinates. Only games to come need theirs. */
@Component
class GamePins extends PinTable {

	GamePins(JdbcClient jdbc) {
		super(jdbc, "games", "starts_at > now() AND status <> 'cancelled'");
	}

}
