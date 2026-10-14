package com.playchale.api.users.api;

import java.util.UUID;

/**
 * A guest host can sign in now: someone signed in with the number they hosted under, or signed in
 * on their device with a number, email or Google account nobody had. The same account, so their
 * games stay put; it just isn't a guest any more. Published in that transaction.
 */
public record GuestHostBecameMember(UUID userId) {
}
