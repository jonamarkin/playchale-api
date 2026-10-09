package com.playchale.api.auth.api;

import java.util.UUID;

/**
 * Someone just proved they hold a phone number (E.164) or an email address (lower-case): they signed
 * in with a code sent to it, added it as a way of signing in, or signed in with a Google account
 * whose email Google has verified. One of the two is set.
 *
 * <p>Published in the sign-in's own transaction. Anything held under that number or address for
 * someone without an account yet, such as a guest spot in a game, can now be theirs.
 */
public record ContactVerified(UUID userId, String phone, String email) {
}
