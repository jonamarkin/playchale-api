package com.playchale.api.games.internal.repository;

import com.playchale.api.shared.maps.PinTable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Repeating games' pins, copied to each game they open. A stopped one won't open any more. */
@Component
class SeriesPins extends PinTable {

	SeriesPins(JdbcClient jdbc) {
		super(jdbc, "game_series", "status <> 'stopped'");
	}

}
