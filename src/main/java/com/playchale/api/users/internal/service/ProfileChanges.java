package com.playchale.api.users.internal.service;

import java.util.List;

/**
 * What a player changed about themselves. A null field is left as it was; a blank optional field
 * (area, position, payout number) clears it.
 */
public record ProfileChanges(String name, String handle, String area, List<String> sports, String position,
		String payoutPhone) {
}
