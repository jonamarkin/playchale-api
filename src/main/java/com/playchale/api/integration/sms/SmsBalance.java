package com.playchale.api.integration.sms;

import java.util.OptionalLong;

/** How many texts the SMS bundle has left, for whoever keeps it topped up. */
public interface SmsBalance {

	/** Credits left, or empty when the provider couldn't be asked just now. */
	OptionalLong credits();

	/** Below this, it's time to top up. */
	long lowAt();

}
