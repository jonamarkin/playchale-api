package com.playchale.api.venues.internal.repository;

import com.playchale.api.shared.maps.PinTable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Venues' pins, for the daily look-up of place coordinates. Every venue's is still in use. */
@Component
class VenuePins extends PinTable {

	VenuePins(JdbcClient jdbc) {
		super(jdbc, "venues", "listed");
	}

}
