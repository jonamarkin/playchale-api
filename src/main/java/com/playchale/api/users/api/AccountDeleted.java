package com.playchale.api.users.api;

import java.util.UUID;

/**
 * A player deleted their account. Published in the same transaction, so each module removes what it
 * holds about them (sessions, notifications, spots they hadn't paid for) together with the account.
 *
 * @param phone       the number they signed in with, if any, now removed from the account
 * @param signInEmail the email they signed in with, if any, now removed from the account
 */
public record AccountDeleted(UUID userId, String phone, String signInEmail) {
}
