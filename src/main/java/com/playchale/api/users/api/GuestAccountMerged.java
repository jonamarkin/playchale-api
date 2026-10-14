package com.playchale.api.users.api;

import java.util.UUID;

/**
 * A guest host signed in to an account they already had (or that account added the number they
 * hosted under): what the guest account holds moves to {@code into}, each module its own, in the
 * same transaction. The guest account is then retired.
 */
public record GuestAccountMerged(UUID guestId, UUID into) {
}
