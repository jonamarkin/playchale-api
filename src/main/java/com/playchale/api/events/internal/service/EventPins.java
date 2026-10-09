package com.playchale.api.events.internal.service;

import com.playchale.api.shared.maps.PinTable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Events' pins, for the daily look-up of place coordinates. Only events still to finish need theirs. */
@Component
class EventPins extends PinTable {

	EventPins(JdbcClient jdbc) {
		super(jdbc, "events", "ends_on >= current_date AND status <> 'cancelled'");
	}

}
